package com.thinklab.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Secondary aggregate of the IT Backup Registry Service Domain: one run of a backup, or of a restore test, as the tool that did it reported
 * it (ADR-030). It is a fact, so it is immutable: never edited, never deleted. A failed run carries one reason from a fixed vocabulary, never
 * text from the tool (which could hold paths or credentials); a successful one carries none. Reporting the same run twice (the same policy,
 * kind and start) is the same run.
 */
public class BackupRun {

    /** How far ahead of the registry's clock a run may claim to have finished: the tool's clock is allowed to be a little off. */
    public static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private final UUID id;
    private final UUID organisationId;
    private final UUID policyId;
    private final RunKind kind;
    private final Outcome outcome;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final Long sizeBytes;
    private final FailureReason failureReason;
    private final String reportedBy;
    private final Instant reportedAt;

    private BackupRun(UUID id, UUID organisationId, UUID policyId, RunKind kind, Outcome outcome, Instant startedAt, Instant finishedAt, Long sizeBytes,
                      FailureReason failureReason, String reportedBy, Instant reportedAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.policyId = policyId;
        this.kind = kind;
        this.outcome = outcome;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.sizeBytes = sizeBytes;
        this.failureReason = failureReason;
        this.reportedBy = reportedBy;
        this.reportedAt = reportedAt;
    }

    public static BackupRun createNew(UUID id, UUID organisationId, UUID policyId, RunKind kind, Outcome outcome, Instant startedAt, Instant finishedAt, Long sizeBytes,
                                      FailureReason failureReason, String executor, Instant now) {
        if (id == null || organisationId == null || policyId == null) {
            throw new IllegalArgumentException("ID, Organisation ID and Policy ID are mandatory for a BackupRun.");
        }
        if (kind == null || outcome == null) {
            throw new IllegalArgumentException("The kind and the outcome of the run are mandatory.");
        }
        if (startedAt == null || finishedAt == null) {
            throw new IllegalArgumentException("When the run started and when it finished are mandatory.");
        }
        startedAt = truncate(startedAt);
        finishedAt = truncate(finishedAt);
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("A run cannot finish before it started.");
        }
        if (finishedAt.isAfter(now.plus(CLOCK_SKEW))) {
            throw new IllegalArgumentException("A run cannot finish in the future.");
        }
        if (outcome == Outcome.FAILED && failureReason == null) {
            throw new IllegalArgumentException("A failed run needs a reason.");
        }
        if (outcome == Outcome.SUCCEEDED && failureReason != null) {
            throw new IllegalArgumentException("A successful run has no failure reason.");
        }
        if (sizeBytes != null && sizeBytes < 0) {
            throw new IllegalArgumentException("The size of a run cannot be negative.");
        }
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory to report a BackupRun.");
        }
        return new BackupRun(id, organisationId, policyId, kind, outcome, startedAt, finishedAt, sizeBytes, failureReason, executor, now);
    }

    public static BackupRun reconstitute(UUID id, UUID organisationId, UUID policyId, RunKind kind, Outcome outcome, Instant startedAt, Instant finishedAt, Long sizeBytes,
                                         FailureReason failureReason, String reportedBy, Instant reportedAt) {
        if (id == null || organisationId == null || policyId == null || kind == null || outcome == null || startedAt == null || finishedAt == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Policy ID, kind, outcome and times are mandatory to reconstitute a BackupRun.");
        }
        return new BackupRun(id, organisationId, policyId, kind, outcome, startedAt, finishedAt, sizeBytes, failureReason, reportedBy, reportedAt);
    }

    /** The store keeps milliseconds, and the start is part of the run's identity: every time is cut to the millisecond before it is compared or looked up. */
    public static Instant truncate(Instant moment) {
        return moment.truncatedTo(ChronoUnit.MILLIS);
    }

    /** How long it took, in whole minutes rounded up and never less than one: what a restore test is judged against the recovery time objective with. */
    public int durationMinutes() {
        long seconds = Duration.between(startedAt, finishedAt).getSeconds();
        return (int) Math.max(1, (seconds + 59) / 60);
    }

    public boolean succeeded() {
        return outcome == Outcome.SUCCEEDED;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getPolicyId() { return policyId; }
    public RunKind getKind() { return kind; }
    public Outcome getOutcome() { return outcome; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Long getSizeBytes() { return sizeBytes; }
    public FailureReason getFailureReason() { return failureReason; }
    public String getReportedBy() { return reportedBy; }
    public Instant getReportedAt() { return reportedAt; }

    /** A backup, or a test that a backup can really be restored. */
    public enum RunKind { BACKUP, RESTORE_TEST }

    public enum Outcome { SUCCEEDED, FAILED }

    /** The fixed vocabulary of failures: never the tool's own words. */
    public enum FailureReason { TIMEOUT, STORAGE_FULL, SOURCE_UNREACHABLE, AUTH_FAILED, CHECKSUM_MISMATCH, OTHER }
}
