package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.DuplicateBackupPolicyException;
import com.thinklab.domain.exception.InvalidBackupPolicyStatusException;
import com.thinklab.domain.model.BackupPolicy;
import com.thinklab.domain.model.BackupPolicy.PolicyAuditEntry;
import com.thinklab.domain.model.BackupPolicy.PolicyStatus;
import com.thinklab.domain.model.BackupRun;
import com.thinklab.domain.model.BackupRun.RunKind;
import com.thinklab.domain.repository.BackupPolicyRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.BackupPolicyDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.BackupPolicyDocument.AuditEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.BackupPolicyDocument.BackupPolicyPersistenceMapper;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter for the BackupPolicy aggregate, raw reactive-streams driver. Every change is one atomic update guarded by
 * the status loaded (ADR-033) that also appends the audit entry; a name unique per organisation is the atomic backstop of a duplicate. The
 * summary of the reported runs only ever moves forward in time ({@code $max} and a compare on the restore test), so it does not matter in
 * which order, or how many times, the reports arrive (ADR-030).
 */
@Singleton
public class BackupPolicyMongoRepositoryAdapter implements BackupPolicyRepository {

    private static final Logger log = LoggerFactory.getLogger(BackupPolicyMongoRepositoryAdapter.class);
    static final String DEFAULT_DATABASE = "thinklab_it_backup_registry_db";
    static final String COLLECTION_NAME = "backup_policies";
    static final int DUPLICATE_KEY = 11000;
    private static final String FIELD_ID = "_id";
    private static final String FIELD_ORGANISATION = "organisationId";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_LAST_RESTORE_TEST = "lastRestoreTestAt";

    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;
    private final String database;

    public BackupPolicyMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : DEFAULT_DATABASE;
    }

    private MongoCollection<BackupPolicyDocument> getCollection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION_NAME, BackupPolicyDocument.class).withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<BackupPolicy> create(BackupPolicy policy) {
        log.debug("[PERSISTENCE] Monolithic create for BackupPolicy Aggregate: {}", policy.getId());

        return Mono.from(getCollection().insertOne(BackupPolicyPersistenceMapper.toDocument(policy)))
                .map(result -> policy)
                .onErrorMap(MongoWriteException.class, error -> duplicate(error, policy));
    }

    @Override
    public Mono<BackupPolicy> findById(UUID id, UUID organisationId) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq(FIELD_ID, id), Filters.eq(FIELD_ORGANISATION, organisationId))).first())
                .map(BackupPolicyPersistenceMapper::toDomain);
    }

    @Override
    public Flux<BackupPolicy> findAll(UUID organisationId) {
        return Flux.from(getCollection().find(Filters.eq(FIELD_ORGANISATION, organisationId)).sort(Sorts.ascending("createdAt"))).map(BackupPolicyPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Void> save(BackupPolicy policy, PolicyStatus expectedStatus, PolicyAuditEntry auditEntry) {
        Bson guard = Filters.and(Filters.eq(FIELD_ID, policy.getId()), Filters.eq(FIELD_ORGANISATION, policy.getOrganisationId()), Filters.eq(FIELD_STATUS, expectedStatus.name()));
        Bson update = Updates.combine(
                Updates.set("name", policy.getName()),
                Updates.set("assetId", policy.getAssetId()),
                Updates.set("frequencyHours", policy.getFrequencyHours()),
                Updates.set("rpoHours", policy.getRpoHours()),
                Updates.set("rtoMinutes", policy.getRtoMinutes()),
                Updates.set("retentionDays", policy.getRetentionDays()),
                Updates.set("restoreTestEveryDays", policy.getRestoreTestEveryDays()),
                Updates.set(FIELD_STATUS, policy.getStatus().name()),
                Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", AuditEntryDocument.fromDomain(auditEntry))
        );
        return Mono.from(getCollection().updateOne(guard, update))
                .flatMap(result -> result.getMatchedCount() == 0
                        ? Mono.error(new InvalidBackupPolicyStatusException("BackupPolicy was changed by someone else while this change was being recorded; read it again and retry."))
                        : Mono.<Void>empty())
                .onErrorMap(MongoWriteException.class, error -> duplicate(error, policy));
    }

    @Override
    public Mono<Void> recordRun(UUID policyId, UUID organisationId, BackupRun run) {
        Bson owner = Filters.and(Filters.eq(FIELD_ID, policyId), Filters.eq(FIELD_ORGANISATION, organisationId));
        List<Bson> forward = new ArrayList<>();
        forward.add(Updates.max("lastRunAt", run.getFinishedAt()));
        boolean success = run.succeeded();
        if (success && run.getKind() == RunKind.BACKUP) {
            forward.add(Updates.max("lastSuccessAt", run.getFinishedAt()));
        }
        Mono<Void> summary = Mono.from(getCollection().updateOne(owner, Updates.combine(forward))).then();
        if (!(success && run.getKind() == RunKind.RESTORE_TEST)) {
            return summary;
        }
        Bson newer = Filters.and(owner, Filters.or(Filters.eq(FIELD_LAST_RESTORE_TEST, null), Filters.lt(FIELD_LAST_RESTORE_TEST, run.getFinishedAt())));
        Bson restore = Updates.combine(Updates.set(FIELD_LAST_RESTORE_TEST, run.getFinishedAt()), Updates.set("lastRestoreMinutes", run.durationMinutes()));
        return summary.then(Mono.from(getCollection().updateOne(newer, restore))).then();
    }

    private static Throwable duplicate(MongoWriteException error, BackupPolicy policy) {
        return error.getError().getCode() == DUPLICATE_KEY
                ? new DuplicateBackupPolicyException("A backup policy named '" + policy.getName() + "' already exists in this organisation.") : error;
    }
}
