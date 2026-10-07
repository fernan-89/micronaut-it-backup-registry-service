package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.model.BackupRun;
import com.thinklab.domain.model.BackupRun.RunKind;
import com.thinklab.domain.repository.BackupRunRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.BackupRunDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.BackupRunDocument.BackupRunPersistenceMapper;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter for the BackupRun aggregate, raw reactive-streams driver. Runs are only ever inserted. The unique
 * {@code (organisationId, policyId, kind, startedAt)} index is what makes a repeated report the same run: the loser of an insert reads the
 * stored one (ADR-030).
 */
@Singleton
public class BackupRunMongoRepositoryAdapter implements BackupRunRepository {

    static final String COLLECTION_NAME = "backup_runs";
    private static final String FIELD_ID = "_id";
    private static final String FIELD_ORGANISATION = "organisationId";

    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;
    private final String database;

    public BackupRunMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : BackupPolicyMongoRepositoryAdapter.DEFAULT_DATABASE;
    }

    private MongoCollection<BackupRunDocument> getCollection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION_NAME, BackupRunDocument.class).withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<Stored> create(BackupRun run) {
        return Mono.from(getCollection().insertOne(BackupRunPersistenceMapper.toDocument(run)))
                .map(result -> new Stored(run, true))
                .onErrorResume(MongoWriteException.class, error -> error.getError().getCode() == BackupPolicyMongoRepositoryAdapter.DUPLICATE_KEY
                        ? findByNaturalKey(run.getOrganisationId(), run.getPolicyId(), run.getKind(), run.getStartedAt()).map(existing -> new Stored(existing, false))
                        : Mono.error(error));
    }

    @Override
    public Mono<BackupRun> findByNaturalKey(UUID organisationId, UUID policyId, RunKind kind, Instant startedAt) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq(FIELD_ORGANISATION, organisationId), Filters.eq("policyId", policyId),
                        Filters.eq("kind", kind.name()), Filters.eq("startedAt", startedAt))).first())
                .map(BackupRunPersistenceMapper::toDomain);
    }

    @Override
    public Mono<BackupRun> findById(UUID id, UUID organisationId) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq(FIELD_ID, id), Filters.eq(FIELD_ORGANISATION, organisationId))).first())
                .map(BackupRunPersistenceMapper::toDomain);
    }

    @Override
    public Flux<BackupRun> findAll(UUID organisationId, Filter filter, int limit) {
        List<Bson> conditions = new ArrayList<>();
        conditions.add(Filters.eq(FIELD_ORGANISATION, organisationId));
        if (filter.policyId() != null) {
            conditions.add(Filters.eq("policyId", filter.policyId()));
        }
        if (filter.kind() != null) {
            conditions.add(Filters.eq("kind", filter.kind().name()));
        }
        if (filter.outcome() != null) {
            conditions.add(Filters.eq("outcome", filter.outcome().name()));
        }
        return Flux.from(getCollection().find(Filters.and(conditions)).sort(Sorts.descending("startedAt")).limit(limit)).map(BackupRunPersistenceMapper::toDomain);
    }
}
