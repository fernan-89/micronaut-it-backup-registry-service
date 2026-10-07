package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.micronaut.context.event.StartupEvent;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class BackupIndexInitializerTest {

    private final MongoClient client = mock(MongoClient.class);

    @Test
    @DisplayName("startup creates the unique policy name, the unique run key, and the run list index")
    void indexes() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> policies = mock(MongoCollection.class);
        MongoCollection<Document> runs = mock(MongoCollection.class);
        when(client.getDatabase("tenant_bkp")).thenReturn(database);
        when(database.getCollection("backup_policies")).thenReturn(policies);
        when(database.getCollection("backup_runs")).thenReturn(runs);
        when(policies.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("ok"));
        when(runs.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("ok"));

        new BackupIndexInitializer(client, "mongodb://mongo:27017/tenant_bkp").onApplicationEvent(mock(StartupEvent.class));

        ArgumentCaptor<IndexOptions> policyOptions = ArgumentCaptor.forClass(IndexOptions.class);
        verify(policies).createIndex(any(), policyOptions.capture());
        assertTrue(policyOptions.getValue().isUnique());
        assertEquals("organisationId_1_name_1", policyOptions.getValue().getName());
        ArgumentCaptor<IndexOptions> runOptions = ArgumentCaptor.forClass(IndexOptions.class);
        verify(runs, times(2)).createIndex(any(), runOptions.capture());
        assertTrue(runOptions.getAllValues().get(0).isUnique());
        assertEquals("organisationId_1_policyId_1_kind_1_startedAt_1", runOptions.getAllValues().get(0).getName());
        assertFalse(runOptions.getAllValues().get(1).isUnique());
        assertEquals("organisationId_1_policyId_1_startedAt_-1", runOptions.getAllValues().get(1).getName());
    }

    @Test
    @DisplayName("fail-open: an unreachable server or a rejected index is logged, never propagated; the URI may omit the database; arguments are null-checked")
    void failOpen() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(client.getDatabase("thinklab_it_backup_registry_db")).thenReturn(database);
        when(database.getCollection(any(String.class))).thenReturn(collection);
        when(collection.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.error(new MongoTimeoutException("no server")))
                .thenReturn(Mono.error(new IllegalStateException("rejected"))).thenReturn(Mono.just("ok"));
        var initializer = new BackupIndexInitializer(client, "mongodb://mongo:27017");

        initializer.onApplicationEvent(mock(StartupEvent.class));

        verify(collection, times(3)).createIndex(any(), any(IndexOptions.class));
        assertThrows(NullPointerException.class, () -> initializer.onApplicationEvent(null));
        assertThrows(NullPointerException.class, () -> new BackupIndexInitializer(null, "mongodb://mongo:27017"));
        assertThrows(NullPointerException.class, () -> new BackupIndexInitializer(client, null));
    }
}
