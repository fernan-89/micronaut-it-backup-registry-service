package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidBackupPolicyStatusException;
import com.thinklab.domain.model.BackupPolicy.Assessment;
import com.thinklab.domain.model.BackupPolicy.PolicyStatus;
import com.thinklab.domain.model.BackupPolicy.Protection;
import com.thinklab.domain.model.BackupPolicy.RestoreTestStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupPolicyTest {

    private final UUID org = UUID.randomUUID();
    private final UUID asset = UUID.randomUUID();

    private BackupPolicy policy(Integer restoreEvery) {
        return BackupPolicy.createNew(UUID.randomUUID(), org, "Database nightly", asset, 24, 48, 120, 30, restoreEvery, "op");
    }

    private BackupPolicy reconstituted(PolicyStatus status, Instant createdAt, Instant lastSuccess, Instant lastRestore, Integer restoreMinutes, Integer restoreEvery) {
        return BackupPolicy.reconstitute(UUID.randomUUID(), org, "P", asset, 24, 48, 120, 30, restoreEvery, status, createdAt, createdAt, null, lastSuccess, lastRestore,
                restoreMinutes, List.of());
    }

    @Test
    @DisplayName("a new policy is ACTIVE, with one INITIATED entry, and the summary of the runs is empty")
    void createNew() {
        BackupPolicy policy = policy(7);

        assertEquals(PolicyStatus.ACTIVE, policy.getStatus());
        assertEquals("Database nightly", policy.getName());
        assertEquals(asset, policy.getAssetId());
        assertEquals(org, policy.getOrganisationId());
        assertEquals(24, policy.getFrequencyHours());
        assertEquals(48, policy.getRpoHours());
        assertEquals(120, policy.getRtoMinutes());
        assertEquals(30, policy.getRetentionDays());
        assertEquals(7, policy.getRestoreTestEveryDays());
        assertNull(policy.getLastRunAt());
        assertNull(policy.getLastSuccessAt());
        assertNull(policy.getLastRestoreTestAt());
        assertNull(policy.getLastRestoreMinutes());
        assertEquals(policy.getCreatedAt(), policy.getUpdatedAt());
        assertEquals(1, policy.getAuditTrail().size());
        assertEquals("INITIATED", policy.getAuditTrail().get(0).action());
        assertNull(policy.getAuditTrail().get(0).fromStatus());
        assertThrows(UnsupportedOperationException.class, () -> policy.getAuditTrail().clear());
    }

    @Test
    @DisplayName("creation refuses what makes no sense, and says why")
    void createRefuses() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.createNew(null, org, "N", asset, 24, 48, 120, 30, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.createNew(id, null, "N", asset, 24, 48, 120, 30, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.createNew(id, org, null, asset, 24, 48, 120, 30, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.createNew(id, org, " ", asset, 24, 48, 120, 30, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.createNew(id, org, "x".repeat(81), asset, 24, 48, 120, 30, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.createNew(id, org, "N", null, 24, 48, 120, 30, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.createNew(id, org, "N", asset, 0, 48, 120, 30, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.createNew(id, org, "N", asset, 8761, 48, 120, 30, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.createNew(id, org, "N", asset, 24, 0, 120, 30, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.createNew(id, org, "N", asset, 24, 8761, 120, 30, null, "op"));
        IllegalArgumentException rpo = assertThrows(IllegalArgumentException.class, () -> BackupPolicy.createNew(id, org, "N", asset, 24, 12, 120, 30, null, "op"));
        assertTrue(rpo.getMessage().contains("cannot be shorter"));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.createNew(id, org, "N", asset, 24, 48, 0, 30, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.createNew(id, org, "N", asset, 24, 48, 43201, 30, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.createNew(id, org, "N", asset, 24, 48, 120, 0, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.createNew(id, org, "N", asset, 24, 48, 120, 3651, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.createNew(id, org, "N", asset, 24, 48, 120, 30, 0, "op"));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.createNew(id, org, "N", asset, 24, 48, 120, 30, 366, "op"));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.createNew(id, org, "N", asset, 24, 48, 120, 30, null, null));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.createNew(id, org, "N", asset, 24, 48, 120, 30, null, " "));
    }

    @Test
    @DisplayName("the limits themselves are accepted")
    void limits() {
        BackupPolicy policy = BackupPolicy.createNew(UUID.randomUUID(), org, "x".repeat(80), asset, 1, 8760, 43200, 3650, 365, "op");
        assertEquals(8760, policy.getRpoHours());
        assertEquals(24, BackupPolicy.createNew(UUID.randomUUID(), org, "N", asset, 24, 24, 1, 1, 1, "op").getRpoHours());
    }

    @Test
    @DisplayName("update replaces the whole definition in any status and keeps the summary of the runs")
    void update() {
        BackupPolicy policy = policy(null);
        UUID other = UUID.randomUUID();

        var entry = policy.update("Renamed", other, 12, 24, 60, 90, 14, "op2");

        assertEquals("UPDATED", entry.action());
        assertEquals(PolicyStatus.ACTIVE, entry.fromStatus());
        assertEquals("Renamed", policy.getName());
        assertEquals(other, policy.getAssetId());
        assertEquals(12, policy.getFrequencyHours());
        assertEquals(24, policy.getRpoHours());
        assertEquals(60, policy.getRtoMinutes());
        assertEquals(90, policy.getRetentionDays());
        assertEquals(14, policy.getRestoreTestEveryDays());
        assertEquals(2, policy.getAuditTrail().size());
        assertThrows(IllegalArgumentException.class, () -> policy.update(" ", other, 12, 24, 60, 90, 14, "op"));
        assertThrows(IllegalArgumentException.class, () -> policy.update("N", other, 12, 24, 60, 90, 14, null));
        policy.pause("op");
        assertEquals("UPDATED", policy.update("Again", other, 12, 24, 60, 90, null, "op").action());
        assertEquals(PolicyStatus.PAUSED, policy.getStatus());
    }

    @Test
    @DisplayName("pause and resume are the two legal transitions, anything else is refused")
    void pauseResume() {
        BackupPolicy policy = policy(null);

        assertThrows(InvalidBackupPolicyStatusException.class, () -> policy.resume("op"));
        assertEquals("PAUSED", policy.pause("op").action());
        assertEquals(PolicyStatus.PAUSED, policy.getStatus());
        assertThrows(InvalidBackupPolicyStatusException.class, () -> policy.pause("op"));
        assertThrows(IllegalArgumentException.class, () -> policy.resume(null));
        assertEquals("RESUMED", policy.resume("op").action());
        assertEquals(PolicyStatus.ACTIVE, policy.getStatus());
        assertThrows(IllegalArgumentException.class, () -> policy.pause(" "));
    }

    @Test
    @DisplayName("a policy never backed up is NEVER inside the RPO of its creation, and RPO_BREACHED after it")
    void neverThenBreached() {
        Instant created = Instant.parse("2026-10-01T00:00:00Z");
        BackupPolicy policy = reconstituted(PolicyStatus.ACTIVE, created, null, null, null, null);

        Assessment inside = policy.assess(created.plus(Duration.ofHours(47)));
        assertEquals(Protection.NEVER, inside.protection());
        assertEquals(created.plus(Duration.ofHours(48)), inside.protectedUntil());
        assertEquals(RestoreTestStatus.NOT_REQUIRED, inside.restoreTest());
        assertNull(inside.restoreTestDueAt());
        assertNull(inside.rtoMet());

        assertEquals(Protection.RPO_BREACHED, policy.assess(created.plus(Duration.ofHours(49))).protection());
    }

    @Test
    @DisplayName("a successful backup protects for the RPO, the last one counts")
    void okThenBreached() {
        Instant created = Instant.parse("2026-10-01T00:00:00Z");
        Instant success = Instant.parse("2026-10-05T00:00:00Z");
        BackupPolicy policy = reconstituted(PolicyStatus.ACTIVE, created, success, null, null, null);

        Assessment ok = policy.assess(success.plus(Duration.ofHours(10)));
        assertEquals(Protection.OK, ok.protection());
        assertEquals(success.plus(Duration.ofHours(48)), ok.protectedUntil());
        assertEquals(Protection.RPO_BREACHED, policy.assess(success.plus(Duration.ofHours(49))).protection());
    }

    @Test
    @DisplayName("the promise is broken (RPO) with a fixed code, broken (restore test overdue), kept with a backup inside the RPO, and not judged when paused or never backed up yet (platform ADR-001)")
    void promise() {
        Instant created = Instant.parse("2026-10-01T00:00:00Z");
        Instant success = Instant.parse("2026-10-05T00:00:00Z");
        BackupPolicy ok = reconstituted(PolicyStatus.ACTIVE, created, success, null, null, null);
        BackupPolicy neverYet = reconstituted(PolicyStatus.ACTIVE, created, null, null, null, null);
        BackupPolicy untested = reconstituted(PolicyStatus.ACTIVE, created, success, created, 30, 1);
        BackupPolicy paused = reconstituted(PolicyStatus.PAUSED, created, null, null, 30, 7);

        assertEquals(Promise.kept(), ok.promise(success.plus(Duration.ofHours(10))));
        assertEquals(Promise.broken("BACKUP_RPO_BREACHED"), ok.promise(success.plus(Duration.ofHours(49))));
        assertEquals(Promise.notJudged(), neverYet.promise(created.plus(Duration.ofHours(47))));
        assertEquals(Promise.broken("BACKUP_RPO_BREACHED"), neverYet.promise(created.plus(Duration.ofHours(49))));
        assertEquals(Promise.broken("BACKUP_RESTORE_TEST_OVERDUE"), untested.promise(success.plus(Duration.ofHours(30))));
        assertEquals(Promise.notJudged(), paused.promise(created.plus(Duration.ofDays(400))));
        assertEquals(Promise.Verdict.BROKEN, Promise.broken("X").verdict());
        assertEquals("X", Promise.broken("X").code());
    }

    @Test
    @DisplayName("a policy changed when it was updated or last ran, succeeded or was restore tested, whichever is latest")
    void changedAt() {
        Instant created = Instant.parse("2026-10-01T00:00:00Z");
        Instant success = Instant.parse("2026-10-05T00:00:00Z");
        Instant restore = Instant.parse("2026-10-06T00:00:00Z");
        Instant run = Instant.parse("2026-10-07T00:00:00Z");
        BackupPolicy none = reconstituted(PolicyStatus.ACTIVE, created, null, null, null, null);
        BackupPolicy full = BackupPolicy.reconstitute(UUID.randomUUID(), org, "P", asset, 24, 48, 120, 30, null, PolicyStatus.ACTIVE, created, created, run, success, restore, 30, List.of());
        BackupPolicy olderThanUpdate = BackupPolicy.reconstitute(UUID.randomUUID(), org, "P", asset, 24, 48, 120, 30, null, PolicyStatus.ACTIVE, created, restore, created, success, created, 30, List.of());

        assertEquals(created, none.changedAt());
        assertEquals(run, full.changedAt());
        assertEquals(restore, olderThanUpdate.changedAt());
    }

    @Test
    @DisplayName("a paused policy is not judged")
    void paused() {
        Instant created = Instant.parse("2026-10-01T00:00:00Z");
        BackupPolicy policy = reconstituted(PolicyStatus.PAUSED, created, null, null, 30, 7);

        Assessment judged = policy.assess(created.plus(Duration.ofDays(400)));

        assertEquals(Protection.PAUSED, judged.protection());
        assertNull(judged.protectedUntil());
        assertEquals(RestoreTestStatus.NOT_REQUIRED, judged.restoreTest());
        assertNull(judged.restoreTestDueAt());
        assertEquals(true, judged.rtoMet());
    }

    @Test
    @DisplayName("a restore test is due after the last successful one, or after the creation, and its time is judged against the RTO")
    void restoreTests() {
        Instant created = Instant.parse("2026-10-01T00:00:00Z");
        Instant success = Instant.parse("2026-10-09T00:00:00Z");
        BackupPolicy never = reconstituted(PolicyStatus.ACTIVE, created, success, null, null, 7);

        Assessment fromCreation = never.assess(created.plus(Duration.ofDays(6)));
        assertEquals(RestoreTestStatus.OK, fromCreation.restoreTest());
        assertEquals(created.plus(Duration.ofDays(7)), fromCreation.restoreTestDueAt());
        assertEquals(RestoreTestStatus.OVERDUE, never.assess(created.plus(Duration.ofDays(8))).restoreTest());

        Instant tested = Instant.parse("2026-10-08T00:00:00Z");
        BackupPolicy tooSlow = reconstituted(PolicyStatus.ACTIVE, created, success, tested, 121, 7);
        assertEquals(tested.plus(Duration.ofDays(7)), tooSlow.assess(tested).restoreTestDueAt());
        assertFalse(tooSlow.assess(tested).rtoMet());
        BackupPolicy fast = reconstituted(PolicyStatus.ACTIVE, created, success, tested, 120, 7);
        assertTrue(fast.assess(tested).rtoMet());
    }

    @Test
    @DisplayName("reconstitute needs the identity, defaults what is missing, and copies the audit trail")
    void reconstitute() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.reconstitute(null, org, "N", asset, 24, 48, 120, 30, null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.reconstitute(id, null, "N", asset, 24, 48, 120, 30, null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.reconstitute(id, org, null, asset, 24, 48, 120, 30, null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.reconstitute(id, org, "N", null, 24, 48, 120, 30, null, null, null, null, null, null, null, null, null));

        BackupPolicy defaults = BackupPolicy.reconstitute(id, org, "N", asset, 24, 48, 120, 30, null, null, null, null, null, null, null, null, null);
        assertEquals(PolicyStatus.ACTIVE, defaults.getStatus());
        assertEquals(defaults.getCreatedAt(), defaults.getUpdatedAt());
        assertTrue(defaults.getAuditTrail().isEmpty());

        Instant created = Instant.parse("2026-10-01T00:00:00Z");
        Instant updated = created.plusSeconds(60);
        BackupPolicy full = BackupPolicy.reconstitute(id, org, "N", asset, 24, 48, 120, 30, 7, PolicyStatus.PAUSED, created, updated, created, created, created, 5,
                List.of(new BackupPolicy.PolicyAuditEntry(created, "INITIATED", "op", null, PolicyStatus.ACTIVE, null)));
        assertEquals(PolicyStatus.PAUSED, full.getStatus());
        assertEquals(created, full.getCreatedAt());
        assertEquals(updated, full.getUpdatedAt());
        assertEquals(created, full.getLastRunAt());
        assertEquals(created, full.getLastSuccessAt());
        assertEquals(created, full.getLastRestoreTestAt());
        assertEquals(5, full.getLastRestoreMinutes());
        assertEquals(1, full.getAuditTrail().size());
        assertEquals(id, full.getId());
    }
}
