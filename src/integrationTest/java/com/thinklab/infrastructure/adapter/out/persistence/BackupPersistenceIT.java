package com.thinklab.infrastructure.adapter.out.persistence;

import com.mongodb.reactivestreams.client.MongoClient;
import com.thinklab.domain.exception.DuplicateBackupPolicyException;
import com.thinklab.domain.exception.InvalidBackupPolicyStatusException;
import com.thinklab.domain.model.BackupPolicy;
import com.thinklab.domain.model.BackupPolicy.PolicyStatus;
import com.thinklab.domain.model.BackupRun;
import com.thinklab.domain.model.BackupRun.FailureReason;
import com.thinklab.domain.model.BackupRun.Outcome;
import com.thinklab.domain.model.BackupRun.RunKind;
import com.thinklab.domain.repository.BackupPolicyRepository;
import com.thinklab.domain.repository.BackupRunRepository;
import com.thinklab.domain.repository.BackupRunRepository.Filter;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backup registry persistence against a real MongoDB: the unique policy name, the unique run key that makes a repeated or simultaneous
 * report the same run, the summary that only moves forward in time whatever the order of the reports, the status-guarded save, and the
 * indexes created at startup.
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackupPersistenceIT implements TestPropertyProvider {

    private static final String DATABASE = "backup_registry_it";

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE));
    }

    @Inject BackupPolicyRepository policies;
    @Inject BackupRunRepository runs;
    @Inject MongoClient mongoClient;

    private BackupPolicy newPolicy(UUID organisation, String name) {
        return BackupPolicy.createNew(UUID.randomUUID(), organisation, name, UUID.randomUUID(), 24, 48, 120, 30, 7, "op-1");
    }

    private BackupRun newRun(UUID organisation, UUID policy, RunKind kind, Outcome outcome, Instant start, Instant end) {
        return BackupRun.createNew(UUID.randomUUID(), organisation, policy, kind, outcome, start, end, null, outcome == Outcome.FAILED ? FailureReason.TIMEOUT : null, "tool", end);
    }

    @Test
    @DisplayName("a policy is read back whole, only for its own organisation, oldest first; the name is unique per organisation")
    void policyRoundTrip() {
        UUID organisation = UUID.randomUUID();
        BackupPolicy first = policies.create(newPolicy(organisation, "First")).block();
        policies.create(newPolicy(organisation, "Second")).block();

        BackupPolicy found = policies.findById(first.getId(), organisation).block();

        assertEquals("First", found.getName());
        assertEquals(7, found.getRestoreTestEveryDays());
        assertEquals(1, found.getAuditTrail().size());
        assertNull(policies.findById(first.getId(), UUID.randomUUID()).block());
        assertEquals(List.of("First", "Second"), policies.findAll(organisation).map(BackupPolicy::getName).collectList().block());
        assertThrows(DuplicateBackupPolicyException.class, () -> policies.create(newPolicy(organisation, "First")).block());
        assertEquals("First", policies.create(newPolicy(UUID.randomUUID(), "First")).block().getName());
    }

    @Test
    @DisplayName("a change is guarded by the status loaded: the second writer from the same state loses, and the audit entry is kept")
    void guardedSave() {
        UUID organisation = UUID.randomUUID();
        BackupPolicy created = policies.create(newPolicy(organisation, "Guarded")).block();
        BackupPolicy writerOne = policies.findById(created.getId(), organisation).block();
        BackupPolicy writerTwo = policies.findById(created.getId(), organisation).block();

        var entry = writerOne.pause("op-1");
        policies.save(writerOne, PolicyStatus.ACTIVE, entry).block();
        var other = writerTwo.pause("op-2");

        assertThrows(InvalidBackupPolicyStatusException.class, () -> policies.save(writerTwo, PolicyStatus.ACTIVE, other).block());
        BackupPolicy stored = policies.findById(created.getId(), organisation).block();
        assertEquals(PolicyStatus.PAUSED, stored.getStatus());
        assertEquals(2, stored.getAuditTrail().size());
    }

    @Test
    @DisplayName("a rename to a name already in use is a duplicate")
    void renameDuplicate() {
        UUID organisation = UUID.randomUUID();
        policies.create(newPolicy(organisation, "Taken")).block();
        BackupPolicy other = policies.create(newPolicy(organisation, "Free")).block();
        var entry = other.update("Taken", other.getAssetId(), 24, 48, 120, 30, null, "op-1");

        assertThrows(DuplicateBackupPolicyException.class, () -> policies.save(other, PolicyStatus.ACTIVE, entry).block());
    }

    @Test
    @DisplayName("the same run reported twice, even at once, is stored once and the others get the stored one back")
    void sameRun() {
        UUID organisation = UUID.randomUUID();
        UUID policy = policies.create(newPolicy(organisation, "Runs")).block().getId();
        Instant start = Instant.now().minusSeconds(3600);
        Instant end = start.plusSeconds(600);

        List<BackupRunRepository.Stored> results = Flux.range(0, 20).flatMap(i -> runs.create(newRun(organisation, policy, RunKind.BACKUP, Outcome.SUCCEEDED, start, end))).collectList().block();

        assertEquals(1, results.stream().filter(BackupRunRepository.Stored::created).count());
        assertEquals(1, results.stream().map(stored -> stored.run().getId()).distinct().count());
        assertEquals(1, runs.findAll(organisation, new Filter(policy, null, null), 50).count().block());
        assertTrue(runs.findByNaturalKey(organisation, policy, RunKind.BACKUP, BackupRun.truncate(start)).block() != null);
        assertNull(runs.findByNaturalKey(organisation, policy, RunKind.RESTORE_TEST, BackupRun.truncate(start)).block());
    }

    @Test
    @DisplayName("runs are listed newest start first, filtered, tenant-scoped and limited")
    void listing() {
        UUID organisation = UUID.randomUUID();
        UUID policy = policies.create(newPolicy(organisation, "Listing")).block().getId();
        Instant base = Instant.now().minusSeconds(86400);
        BackupRun old = runs.create(newRun(organisation, policy, RunKind.BACKUP, Outcome.SUCCEEDED, base, base.plusSeconds(60))).block().run();
        BackupRun failed = runs.create(newRun(organisation, policy, RunKind.BACKUP, Outcome.FAILED, base.plusSeconds(3600), base.plusSeconds(3660))).block().run();
        BackupRun restore = runs.create(newRun(organisation, policy, RunKind.RESTORE_TEST, Outcome.SUCCEEDED, base.plusSeconds(7200), base.plusSeconds(7260))).block().run();

        assertEquals(List.of(restore.getId(), failed.getId(), old.getId()), runs.findAll(organisation, new Filter(null, null, null), 50).map(BackupRun::getId).collectList().block());
        assertEquals(List.of(failed.getId()), runs.findAll(organisation, new Filter(policy, RunKind.BACKUP, Outcome.FAILED), 50).map(BackupRun::getId).collectList().block());
        assertEquals(2, runs.findAll(organisation, new Filter(null, null, null), 2).count().block());
        assertEquals(0, runs.findAll(UUID.randomUUID(), new Filter(null, null, null), 50).count().block());
        assertEquals(FailureReason.TIMEOUT, runs.findById(failed.getId(), organisation).block().getFailureReason());
        assertNull(runs.findById(failed.getId(), UUID.randomUUID()).block());
    }

    @Test
    @DisplayName("the summary only moves forward: a late report changes nothing, and a restore test keeps how long it took")
    void summaryMovesForward() {
        UUID organisation = UUID.randomUUID();
        BackupPolicy policy = policies.create(newPolicy(organisation, "Summary")).block();
        Instant t1 = Instant.now().minusSeconds(7200);
        Instant t2 = t1.plusSeconds(3600);
        BackupRun newer = newRun(organisation, policy.getId(), RunKind.BACKUP, Outcome.SUCCEEDED, t2.minusSeconds(60), t2);
        BackupRun older = newRun(organisation, policy.getId(), RunKind.BACKUP, Outcome.SUCCEEDED, t1.minusSeconds(60), t1);
        BackupRun failedLater = newRun(organisation, policy.getId(), RunKind.BACKUP, Outcome.FAILED, t2.plusSeconds(60), t2.plusSeconds(120));

        policies.recordRun(policy.getId(), organisation, newer).block();
        policies.recordRun(policy.getId(), organisation, older).block();
        policies.recordRun(policy.getId(), organisation, failedLater).block();

        BackupPolicy after = policies.findById(policy.getId(), organisation).block();
        assertEquals(BackupRun.truncate(t2), after.getLastSuccessAt());
        assertEquals(BackupRun.truncate(t2.plusSeconds(120)), after.getLastRunAt());
        assertNull(after.getLastRestoreTestAt());

        BackupRun restoreNew = newRun(organisation, policy.getId(), RunKind.RESTORE_TEST, Outcome.SUCCEEDED, t2.minusSeconds(600), t2);
        BackupRun restoreOld = newRun(organisation, policy.getId(), RunKind.RESTORE_TEST, Outcome.SUCCEEDED, t1.minusSeconds(60), t1);
        policies.recordRun(policy.getId(), organisation, restoreNew).block();
        policies.recordRun(policy.getId(), organisation, restoreOld).block();

        BackupPolicy restored = policies.findById(policy.getId(), organisation).block();
        assertEquals(BackupRun.truncate(t2), restored.getLastRestoreTestAt());
        assertEquals(10, restored.getLastRestoreMinutes());
        assertEquals(BackupRun.truncate(t2), restored.getLastSuccessAt());
        assertFalse(restored.getLastRunAt().isBefore(restored.getLastRestoreTestAt()));
    }

    @Test
    @DisplayName("startup created the unique policy name and the unique run key")
    void indexesExist() {
        List<Document> policyIndexes = Flux.from(mongoClient.getDatabase(DATABASE).getCollection("backup_policies").listIndexes()).collectList().block();
        List<Document> runIndexes = Flux.from(mongoClient.getDatabase(DATABASE).getCollection("backup_runs").listIndexes()).collectList().block();

        assertTrue(policyIndexes.stream().anyMatch(index -> "organisationId_1_name_1".equals(index.getString("name")) && Boolean.TRUE.equals(index.getBoolean("unique"))));
        assertTrue(runIndexes.stream().anyMatch(index -> "organisationId_1_policyId_1_kind_1_startedAt_1".equals(index.getString("name")) && Boolean.TRUE.equals(index.getBoolean("unique"))));
        assertTrue(runIndexes.stream().anyMatch(index -> "organisationId_1_policyId_1_startedAt_-1".equals(index.getString("name"))));
    }
}
