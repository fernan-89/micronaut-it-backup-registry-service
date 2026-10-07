package com.thinklab.domain.model;

import com.thinklab.domain.model.BackupRun.FailureReason;
import com.thinklab.domain.model.BackupRun.Outcome;
import com.thinklab.domain.model.BackupRun.RunKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupRunTest {

    private final UUID id = UUID.randomUUID();
    private final UUID org = UUID.randomUUID();
    private final UUID policy = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-07T12:00:00Z");
    private final Instant start = Instant.parse("2026-10-07T10:00:00.123456Z");
    private final Instant end = Instant.parse("2026-10-07T10:30:00Z");

    private BackupRun run(Outcome outcome, FailureReason reason) {
        return BackupRun.createNew(id, org, policy, RunKind.BACKUP, outcome, start, end, 100L, reason, "tool", now);
    }

    @Test
    @DisplayName("a successful run is a fact: times cut to the millisecond, no reason, duration in minutes")
    void succeeded() {
        BackupRun run = run(Outcome.SUCCEEDED, null);

        assertTrue(run.succeeded());
        assertEquals(Instant.parse("2026-10-07T10:00:00.123Z"), run.getStartedAt());
        assertEquals(30, run.durationMinutes());
        assertEquals(id, run.getId());
        assertEquals(org, run.getOrganisationId());
        assertEquals(policy, run.getPolicyId());
        assertEquals(RunKind.BACKUP, run.getKind());
        assertEquals(Outcome.SUCCEEDED, run.getOutcome());
        assertEquals(end, run.getFinishedAt());
        assertEquals(100L, run.getSizeBytes());
        assertNull(run.getFailureReason());
        assertEquals("tool", run.getReportedBy());
        assertEquals(now, run.getReportedAt());
    }

    @Test
    @DisplayName("a failed run needs a reason; the size may be left out")
    void failed() {
        BackupRun run = BackupRun.createNew(id, org, policy, RunKind.RESTORE_TEST, Outcome.FAILED, start, end, null, FailureReason.TIMEOUT, "tool", now);

        assertFalse(run.succeeded());
        assertEquals(FailureReason.TIMEOUT, run.getFailureReason());
        assertNull(run.getSizeBytes());
        assertThrows(IllegalArgumentException.class, () -> run(Outcome.FAILED, null));
        assertThrows(IllegalArgumentException.class, () -> run(Outcome.SUCCEEDED, FailureReason.OTHER));
    }

    @Test
    @DisplayName("the duration is rounded up and never less than one minute")
    void duration() {
        Instant s = Instant.parse("2026-10-07T10:00:00Z");
        assertEquals(1, BackupRun.createNew(id, org, policy, RunKind.BACKUP, Outcome.SUCCEEDED, s, s, null, null, "t", now).durationMinutes());
        assertEquals(2, BackupRun.createNew(id, org, policy, RunKind.BACKUP, Outcome.SUCCEEDED, s, s.plusSeconds(61), null, null, "t", now).durationMinutes());
        assertEquals(1, BackupRun.createNew(id, org, policy, RunKind.BACKUP, Outcome.SUCCEEDED, s, s.plusSeconds(60), null, null, "t", now).durationMinutes());
    }

    @Test
    @DisplayName("creation refuses missing parts, a time that runs backwards or into the future beyond the clock skew, and a negative size")
    void refuses() {
        assertThrows(IllegalArgumentException.class, () -> BackupRun.createNew(null, org, policy, RunKind.BACKUP, Outcome.SUCCEEDED, start, end, null, null, "t", now));
        assertThrows(IllegalArgumentException.class, () -> BackupRun.createNew(id, null, policy, RunKind.BACKUP, Outcome.SUCCEEDED, start, end, null, null, "t", now));
        assertThrows(IllegalArgumentException.class, () -> BackupRun.createNew(id, org, null, RunKind.BACKUP, Outcome.SUCCEEDED, start, end, null, null, "t", now));
        assertThrows(IllegalArgumentException.class, () -> BackupRun.createNew(id, org, policy, null, Outcome.SUCCEEDED, start, end, null, null, "t", now));
        assertThrows(IllegalArgumentException.class, () -> BackupRun.createNew(id, org, policy, RunKind.BACKUP, null, start, end, null, null, "t", now));
        assertThrows(IllegalArgumentException.class, () -> BackupRun.createNew(id, org, policy, RunKind.BACKUP, Outcome.SUCCEEDED, null, end, null, null, "t", now));
        assertThrows(IllegalArgumentException.class, () -> BackupRun.createNew(id, org, policy, RunKind.BACKUP, Outcome.SUCCEEDED, start, null, null, null, "t", now));
        assertThrows(IllegalArgumentException.class, () -> BackupRun.createNew(id, org, policy, RunKind.BACKUP, Outcome.SUCCEEDED, end, start, null, null, "t", now));
        assertThrows(IllegalArgumentException.class, () -> BackupRun.createNew(id, org, policy, RunKind.BACKUP, Outcome.SUCCEEDED, start, now.plus(BackupRun.CLOCK_SKEW).plusSeconds(1), null, null, "t", now));
        assertThrows(IllegalArgumentException.class, () -> BackupRun.createNew(id, org, policy, RunKind.BACKUP, Outcome.SUCCEEDED, start, end, -1L, null, "t", now));
        assertThrows(IllegalArgumentException.class, () -> BackupRun.createNew(id, org, policy, RunKind.BACKUP, Outcome.SUCCEEDED, start, end, null, null, null, now));
        assertThrows(IllegalArgumentException.class, () -> BackupRun.createNew(id, org, policy, RunKind.BACKUP, Outcome.SUCCEEDED, start, end, null, null, " ", now));
        assertEquals(0L, BackupRun.createNew(id, org, policy, RunKind.BACKUP, Outcome.SUCCEEDED, start, now.plus(BackupRun.CLOCK_SKEW), 0L, null, "t", now).getSizeBytes());
    }

    @Test
    @DisplayName("reconstitute needs the identity, kind, outcome and times, and trusts the rest")
    void reconstitute() {
        assertThrows(IllegalArgumentException.class, () -> BackupRun.reconstitute(null, org, policy, RunKind.BACKUP, Outcome.SUCCEEDED, start, end, null, null, "t", now));
        assertThrows(IllegalArgumentException.class, () -> BackupRun.reconstitute(id, null, policy, RunKind.BACKUP, Outcome.SUCCEEDED, start, end, null, null, "t", now));
        assertThrows(IllegalArgumentException.class, () -> BackupRun.reconstitute(id, org, null, RunKind.BACKUP, Outcome.SUCCEEDED, start, end, null, null, "t", now));
        assertThrows(IllegalArgumentException.class, () -> BackupRun.reconstitute(id, org, policy, null, Outcome.SUCCEEDED, start, end, null, null, "t", now));
        assertThrows(IllegalArgumentException.class, () -> BackupRun.reconstitute(id, org, policy, RunKind.BACKUP, null, start, end, null, null, "t", now));
        assertThrows(IllegalArgumentException.class, () -> BackupRun.reconstitute(id, org, policy, RunKind.BACKUP, Outcome.SUCCEEDED, null, end, null, null, "t", now));
        assertThrows(IllegalArgumentException.class, () -> BackupRun.reconstitute(id, org, policy, RunKind.BACKUP, Outcome.SUCCEEDED, start, null, null, null, "t", now));
        assertEquals(id, BackupRun.reconstitute(id, org, policy, RunKind.BACKUP, Outcome.SUCCEEDED, start, end, null, null, "t", now).getId());
    }
}
