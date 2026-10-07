package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.model.BackupRun;
import com.thinklab.domain.model.BackupRun.FailureReason;
import com.thinklab.domain.model.BackupRun.Outcome;
import com.thinklab.domain.model.BackupRun.RunKind;
import com.thinklab.domain.repository.BackupRunRepository.Filter;
import com.thinklab.infrastructure.adapter.out.persistence.entity.BackupRunDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.BackupRunDocument.BackupRunPersistenceMapper;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class BackupRunMongoAdapterTest {

    private static final String URI = "mongodb://localhost:27017/bkp_test";

    private final UUID org = UUID.randomUUID();
    private MongoClient client;
    private MongoCollection<BackupRunDocument> collection;

    @BeforeEach
    void setUp() {
        client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(client.getDatabase("bkp_test")).thenReturn(database);
        when(client.getDatabase("thinklab_it_backup_registry_db")).thenReturn(database);
        when(database.getCollection("backup_runs", BackupRunDocument.class)).thenReturn(collection);
        when(collection.withCodecRegistry(any())).thenReturn(collection);
    }

    private BackupRun run(Outcome outcome) {
        Instant now = Instant.now();
        return BackupRun.createNew(UUID.randomUUID(), org, UUID.randomUUID(), RunKind.BACKUP, outcome, now.minusSeconds(600), now.minusSeconds(60), 42L,
                outcome == Outcome.FAILED ? FailureReason.TIMEOUT : null, "tool", now);
    }

    private static MongoWriteException writeError(int code) {
        return new MongoWriteException(new WriteError(code, "write error", new BsonDocument()), new ServerAddress());
    }

    private FindPublisher<BackupRunDocument> finds(BackupRun found) {
        FindPublisher<BackupRunDocument> publisher = mock(FindPublisher.class);
        when(collection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(publisher);
        when(publisher.sort(any(Bson.class))).thenReturn(publisher);
        when(publisher.limit(anyInt())).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<BackupRunDocument> subscriber = invocation.getArgument(0);
            Flux.just(BackupRunPersistenceMapper.toDocument(found)).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());
        return publisher;
    }

    @Test
    @DisplayName("a run survives the round trip, with or without a failure reason")
    void roundTrip() {
        BackupRun failed = run(Outcome.FAILED);
        BackupRunDocument document = BackupRunPersistenceMapper.toDocument(failed);
        BackupRun back = BackupRunPersistenceMapper.toDomain(document);

        assertEquals(FailureReason.TIMEOUT, back.getFailureReason());
        assertEquals("TIMEOUT", document.getFailureReason());
        assertEquals("BACKUP", document.getKind());
        assertEquals("FAILED", document.getOutcome());
        assertEquals(42L, document.getSizeBytes());
        assertEquals(org, document.getOrganisationId());
        assertEquals(failed.getPolicyId(), document.getPolicyId());
        assertEquals(failed.getId(), document.getId());
        assertEquals(failed.getStartedAt(), document.getStartedAt());
        assertEquals(failed.getFinishedAt(), document.getFinishedAt());
        assertEquals("tool", document.getReportedBy());
        assertEquals(failed.getReportedAt(), document.getReportedAt());

        BackupRun ok = BackupRunPersistenceMapper.toDomain(BackupRunPersistenceMapper.toDocument(run(Outcome.SUCCEEDED)));
        assertNull(ok.getFailureReason());
        assertTrue(ok.succeeded());
    }

    @Test
    @DisplayName("the persistence mapper is a utility class")
    void mapperIsUtility() throws ReflectiveOperationException {
        Constructor<BackupRunPersistenceMapper> constructor = BackupRunPersistenceMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }

    @Test
    @DisplayName("create answers the run as created; the lookups are scoped to the organisation")
    void lookups() {
        BackupRun run = run(Outcome.SUCCEEDED);
        when(collection.insertOne(any(BackupRunDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));
        finds(run);
        BackupRunMongoRepositoryAdapter adapter = new BackupRunMongoRepositoryAdapter(client, "mongodb://localhost:27017");

        StepVerifier.create(adapter.create(run)).assertNext(stored -> assertTrue(stored.created())).verifyComplete();
        StepVerifier.create(adapter.findById(run.getId(), org)).assertNext(found -> assertEquals(run.getId(), found.getId())).verifyComplete();
        StepVerifier.create(adapter.findByNaturalKey(org, run.getPolicyId(), RunKind.BACKUP, run.getStartedAt())).expectNextCount(1).verifyComplete();
        assertThrows(NullPointerException.class, () -> new BackupRunMongoRepositoryAdapter(client, null));
    }

    @Test
    @DisplayName("a duplicate key means the run was already reported: the stored one comes back, not created; any other error passes through")
    void duplicate() {
        BackupRun run = run(Outcome.SUCCEEDED);
        when(collection.insertOne(any(BackupRunDocument.class))).thenReturn(Mono.error(writeError(11000))).thenReturn(Mono.error(writeError(1)));
        finds(run);
        BackupRunMongoRepositoryAdapter adapter = new BackupRunMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.create(run)).assertNext(stored -> {
            assertFalse(stored.created());
            assertEquals(run.getId(), stored.run().getId());
        }).verifyComplete();
        StepVerifier.create(adapter.create(run)).expectError(MongoWriteException.class).verify();
    }

    @Test
    @DisplayName("the list is filtered only by what is given, newest start first, and limited")
    void list() {
        BackupRun run = run(Outcome.SUCCEEDED);
        FindPublisher<BackupRunDocument> publisher = finds(run);
        BackupRunMongoRepositoryAdapter adapter = new BackupRunMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.findAll(org, new Filter(null, null, null), 5)).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findAll(org, new Filter(run.getPolicyId(), RunKind.BACKUP, Outcome.FAILED), 7)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).find(filter.capture());
        String bare = filter.getAllValues().get(0).toString();
        String full = filter.getAllValues().get(1).toString();
        assertTrue(bare.contains("organisationId") && !bare.contains("policyId") && !bare.contains("outcome"));
        assertTrue(full.contains("policyId") && full.contains("kind") && full.contains("FAILED"));
        verify(publisher).limit(5);
        verify(publisher).limit(7);
    }
}
