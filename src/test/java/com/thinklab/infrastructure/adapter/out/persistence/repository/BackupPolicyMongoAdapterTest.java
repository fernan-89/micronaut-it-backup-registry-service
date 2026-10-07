package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.exception.DuplicateBackupPolicyException;
import com.thinklab.domain.exception.InvalidBackupPolicyStatusException;
import com.thinklab.domain.model.BackupPolicy;
import com.thinklab.domain.model.BackupPolicy.PolicyStatus;
import com.thinklab.domain.model.BackupRun;
import com.thinklab.domain.model.BackupRun.FailureReason;
import com.thinklab.domain.model.BackupRun.Outcome;
import com.thinklab.domain.model.BackupRun.RunKind;
import com.thinklab.infrastructure.adapter.out.persistence.entity.BackupPolicyDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.BackupPolicyDocument.BackupPolicyPersistenceMapper;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class BackupPolicyMongoAdapterTest {

    private static final String URI = "mongodb://localhost:27017/bkp_test";

    private final UUID org = UUID.randomUUID();
    private MongoClient client;
    private MongoCollection<BackupPolicyDocument> collection;

    @BeforeEach
    void setUp() {
        client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(client.getDatabase("bkp_test")).thenReturn(database);
        when(client.getDatabase("thinklab_it_backup_registry_db")).thenReturn(database);
        when(database.getCollection("backup_policies", BackupPolicyDocument.class)).thenReturn(collection);
        when(collection.withCodecRegistry(any())).thenReturn(collection);
    }

    private BackupPolicy policy() {
        BackupPolicy policy = BackupPolicy.createNew(UUID.randomUUID(), org, "Nightly", UUID.randomUUID(), 24, 48, 120, 30, 7, "op");
        policy.pause("op");
        return policy;
    }

    private BackupRun run(RunKind kind, Outcome outcome) {
        Instant now = Instant.now();
        return BackupRun.createNew(UUID.randomUUID(), org, UUID.randomUUID(), kind, outcome, now.minusSeconds(600), now.minusSeconds(60), null,
                outcome == Outcome.FAILED ? FailureReason.OTHER : null, "tool", now);
    }

    private static MongoWriteException writeError(int code) {
        return new MongoWriteException(new WriteError(code, "write error", new BsonDocument()), new ServerAddress());
    }

    private void finds(BackupPolicy found) {
        FindPublisher<BackupPolicyDocument> publisher = mock(FindPublisher.class);
        when(collection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(publisher);
        when(publisher.sort(any(Bson.class))).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<BackupPolicyDocument> subscriber = invocation.getArgument(0);
            Flux.just(BackupPolicyPersistenceMapper.toDocument(found)).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());
    }

    @Test
    @DisplayName("a policy survives the round trip, with its summary of runs and its audit trail")
    void roundTrip() {
        BackupPolicy policy = policy();
        Instant at = Instant.parse("2026-10-07T10:00:00Z");
        BackupPolicy withRuns = BackupPolicy.reconstitute(policy.getId(), org, policy.getName(), policy.getAssetId(), 24, 48, 120, 30, 7, PolicyStatus.PAUSED, at, at, at, at, at, 15,
                policy.getAuditTrail());

        BackupPolicyDocument document = BackupPolicyPersistenceMapper.toDocument(withRuns);
        BackupPolicy back = BackupPolicyPersistenceMapper.toDomain(document);

        assertEquals(PolicyStatus.PAUSED, back.getStatus());
        assertEquals(at, back.getLastRunAt());
        assertEquals(at, back.getLastSuccessAt());
        assertEquals(at, back.getLastRestoreTestAt());
        assertEquals(15, back.getLastRestoreMinutes());
        assertEquals(7, back.getRestoreTestEveryDays());
        assertEquals(2, back.getAuditTrail().size());
        assertNull(back.getAuditTrail().get(0).fromStatus());
        assertEquals(PolicyStatus.ACTIVE, back.getAuditTrail().get(1).fromStatus());
        assertEquals("Nightly", document.getName());
        assertEquals("PAUSED", document.getStatus());
        assertEquals(org, document.getOrganisationId());
        assertEquals(policy.getId(), document.getId());
        assertEquals(policy.getAssetId(), document.getAssetId());
        assertEquals(24, document.getFrequencyHours());
        assertEquals(48, document.getRpoHours());
        assertEquals(120, document.getRtoMinutes());
        assertEquals(30, document.getRetentionDays());
        assertEquals(at, document.getCreatedAt());
        assertEquals(at, document.getUpdatedAt());
        assertEquals(2, document.getAuditTrail().size());
    }

    @Test
    @DisplayName("the document setters are plain, and the persistence mapper is a utility class")
    void plainDocument() throws ReflectiveOperationException {
        BackupPolicyDocument document = new BackupPolicyDocument();
        document.setRestoreTestEveryDays(null);
        document.setLastRestoreMinutes(null);
        document.setAuditTrail(new ArrayList<>());
        assertNull(document.getRestoreTestEveryDays());
        assertNull(document.getLastRestoreMinutes());
        assertTrue(document.getAuditTrail().isEmpty());

        Constructor<BackupPolicyPersistenceMapper> constructor = BackupPolicyPersistenceMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }

    @Test
    @DisplayName("create and the lookups are scoped to the organisation, the list oldest first")
    void lookups() {
        BackupPolicy policy = policy();
        when(collection.insertOne(any(BackupPolicyDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));
        finds(policy);
        BackupPolicyMongoRepositoryAdapter adapter = new BackupPolicyMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.create(policy)).expectNext(policy).verifyComplete();
        StepVerifier.create(adapter.findById(policy.getId(), org)).assertNext(found -> assertEquals(policy.getId(), found.getId())).verifyComplete();
        StepVerifier.create(adapter.findAll(org)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).find(filter.capture());
        assertTrue(filter.getAllValues().get(0).toString().contains("organisationId"));
    }

    @Test
    @DisplayName("a duplicate name is a 409 of the domain, on create or on a rename; any other write error passes through; the URI may omit the database")
    void duplicates() {
        BackupPolicy policy = policy();
        when(collection.insertOne(any(BackupPolicyDocument.class))).thenReturn(Mono.error(writeError(11000))).thenReturn(Mono.error(writeError(1)));
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.error(writeError(11000))).thenReturn(Mono.error(writeError(2)));
        BackupPolicyMongoRepositoryAdapter adapter = new BackupPolicyMongoRepositoryAdapter(client, "mongodb://localhost:27017");

        StepVerifier.create(adapter.create(policy)).expectError(DuplicateBackupPolicyException.class).verify();
        StepVerifier.create(adapter.create(policy)).expectError(MongoWriteException.class).verify();
        StepVerifier.create(adapter.save(policy, PolicyStatus.ACTIVE, policy.getAuditTrail().get(1))).expectError(DuplicateBackupPolicyException.class).verify();
        StepVerifier.create(adapter.save(policy, PolicyStatus.ACTIVE, policy.getAuditTrail().get(1))).expectError(MongoWriteException.class).verify();
        assertThrows(NullPointerException.class, () -> new BackupPolicyMongoRepositoryAdapter(client, null));
    }

    @Test
    @DisplayName("save is guarded by the status loaded and pushes the audit entry; a lost race is a conflict")
    void save() {
        BackupPolicy policy = policy();
        when(collection.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        BackupPolicyMongoRepositoryAdapter adapter = new BackupPolicyMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.save(policy, PolicyStatus.ACTIVE, policy.getAuditTrail().get(1))).verifyComplete();
        StepVerifier.create(adapter.save(policy, PolicyStatus.ACTIVE, policy.getAuditTrail().get(1))).expectError(InvalidBackupPolicyStatusException.class).verify();

        ArgumentCaptor<Bson> guard = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).updateOne(guard.capture(), update.capture());
        assertTrue(guard.getAllValues().get(0).toString().contains("ACTIVE") && guard.getAllValues().get(0).toString().contains("organisationId"));
        assertTrue(update.getAllValues().get(0).toString().contains("auditTrail"));
    }

    @Test
    @DisplayName("a successful backup moves the last run and the last success forward; a failed one only the last run")
    void recordBackups() {
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        BackupPolicyMongoRepositoryAdapter adapter = new BackupPolicyMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.recordRun(UUID.randomUUID(), org, run(RunKind.BACKUP, Outcome.SUCCEEDED))).verifyComplete();
        StepVerifier.create(adapter.recordRun(UUID.randomUUID(), org, run(RunKind.BACKUP, Outcome.FAILED))).verifyComplete();

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).updateOne(any(Bson.class), update.capture());
        assertTrue(update.getAllValues().get(0).toString().contains("lastSuccessAt"));
        assertTrue(update.getAllValues().get(0).toString().contains("lastRunAt"));
        assertFalse(update.getAllValues().get(1).toString().contains("lastSuccessAt"));
    }

    @Test
    @DisplayName("a successful restore test also records itself, only when newer; a failed one only moves the last run")
    void recordRestoreTests() {
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        BackupPolicyMongoRepositoryAdapter adapter = new BackupPolicyMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.recordRun(UUID.randomUUID(), org, run(RunKind.RESTORE_TEST, Outcome.SUCCEEDED))).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).updateOne(filter.capture(), update.capture());
        assertFalse(update.getAllValues().get(0).toString().contains("lastSuccessAt"));
        assertTrue(filter.getAllValues().get(1).toString().contains("lastRestoreTestAt"));
        assertTrue(update.getAllValues().get(1).toString().contains("lastRestoreMinutes"));

        StepVerifier.create(adapter.recordRun(UUID.randomUUID(), org, run(RunKind.RESTORE_TEST, Outcome.FAILED))).verifyComplete();
        verify(collection, times(3)).updateOne(any(Bson.class), any(Bson.class));
    }
}
