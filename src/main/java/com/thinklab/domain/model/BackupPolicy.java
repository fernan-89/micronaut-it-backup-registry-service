package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidBackupPolicyStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate Root (BIAN Control Record) of the IT Backup Registry Service Domain: the promise made about one asset. How often it is backed
 * up ({@code frequencyHours}), how much data may be lost ({@code rpoHours}, the recovery point objective), how long a recovery may take
 * ({@code rtoMinutes}, the recovery time objective), how long copies are kept, and optionally how often a restore must be proved by a test.
 * The tool that does the work reports each run to the registry (ADR-030); the policy keeps only a summary of them (last run, last success,
 * last restore test and how long it took) and judges itself when read (ADR-031): nothing here schedules anything or runs a backup.
 */
public class BackupPolicy {

    public static final int MAX_HOURS = 8760;
    public static final int MAX_RTO_MINUTES = 43200;
    public static final int MAX_RETENTION_DAYS = 3650;
    public static final int MAX_RESTORE_TEST_DAYS = 365;
    public static final int MAX_NAME_LENGTH = 80;

    private final UUID id;
    private final UUID organisationId;
    private String name;
    private UUID assetId;
    private int frequencyHours;
    private int rpoHours;
    private int rtoMinutes;
    private int retentionDays;
    private Integer restoreTestEveryDays;
    private PolicyStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant lastRunAt;
    private Instant lastSuccessAt;
    private Instant lastRestoreTestAt;
    private Integer lastRestoreMinutes;
    private final List<PolicyAuditEntry> auditTrail;

    private BackupPolicy(UUID id, UUID organisationId, String name, UUID assetId, int frequencyHours, int rpoHours, int rtoMinutes, int retentionDays,
                         Integer restoreTestEveryDays, String executor) {
        this.id = id;
        this.organisationId = organisationId;
        this.name = name;
        this.assetId = assetId;
        this.frequencyHours = frequencyHours;
        this.rpoHours = rpoHours;
        this.rtoMinutes = rtoMinutes;
        this.retentionDays = retentionDays;
        this.restoreTestEveryDays = restoreTestEveryDays;
        this.status = PolicyStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.auditTrail = new ArrayList<>();
        this.auditTrail.add(new PolicyAuditEntry(this.createdAt, "INITIATED", executor, null, PolicyStatus.ACTIVE, "Backup policy for asset " + assetId + "."));
    }

    private BackupPolicy(UUID id, UUID organisationId, String name, UUID assetId, int frequencyHours, int rpoHours, int rtoMinutes, int retentionDays,
                         Integer restoreTestEveryDays, PolicyStatus status, Instant createdAt, Instant updatedAt, Instant lastRunAt, Instant lastSuccessAt,
                         Instant lastRestoreTestAt, Integer lastRestoreMinutes, List<PolicyAuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.name = name;
        this.assetId = assetId;
        this.frequencyHours = frequencyHours;
        this.rpoHours = rpoHours;
        this.rtoMinutes = rtoMinutes;
        this.retentionDays = retentionDays;
        this.restoreTestEveryDays = restoreTestEveryDays;
        this.status = status != null ? status : PolicyStatus.ACTIVE;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.lastRunAt = lastRunAt;
        this.lastSuccessAt = lastSuccessAt;
        this.lastRestoreTestAt = lastRestoreTestAt;
        this.lastRestoreMinutes = lastRestoreMinutes;
        this.auditTrail = auditTrail != null ? new ArrayList<>(auditTrail) : new ArrayList<>();
    }

    public static BackupPolicy createNew(UUID id, UUID organisationId, String name, UUID assetId, int frequencyHours, int rpoHours, int rtoMinutes, int retentionDays,
                                         Integer restoreTestEveryDays, String executor) {
        if (id == null || organisationId == null) {
            throw new IllegalArgumentException("ID and Organisation ID are mandatory for BackupPolicy creation.");
        }
        validate(name, assetId, frequencyHours, rpoHours, rtoMinutes, retentionDays, restoreTestEveryDays);
        requireExecutor(executor);
        return new BackupPolicy(id, organisationId, name, assetId, frequencyHours, rpoHours, rtoMinutes, retentionDays, restoreTestEveryDays, executor);
    }

    public static BackupPolicy reconstitute(UUID id, UUID organisationId, String name, UUID assetId, int frequencyHours, int rpoHours, int rtoMinutes, int retentionDays,
                                            Integer restoreTestEveryDays, PolicyStatus status, Instant createdAt, Instant updatedAt, Instant lastRunAt, Instant lastSuccessAt,
                                            Instant lastRestoreTestAt, Integer lastRestoreMinutes, List<PolicyAuditEntry> auditTrail) {
        if (id == null || organisationId == null || name == null || assetId == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Name and Asset ID are mandatory to reconstitute a BackupPolicy.");
        }
        return new BackupPolicy(id, organisationId, name, assetId, frequencyHours, rpoHours, rtoMinutes, retentionDays, restoreTestEveryDays, status, createdAt, updatedAt,
                lastRunAt, lastSuccessAt, lastRestoreTestAt, lastRestoreMinutes, auditTrail);
    }

    // --- Domain Behaviors ---

    /** Behavior Qualifier: {@code update}. The whole definition again, in any status; the summary of the runs is not touched. */
    public PolicyAuditEntry update(String newName, UUID newAssetId, int newFrequencyHours, int newRpoHours, int newRtoMinutes, int newRetentionDays,
                                   Integer newRestoreTestEveryDays, String executor) {
        validate(newName, newAssetId, newFrequencyHours, newRpoHours, newRtoMinutes, newRetentionDays, newRestoreTestEveryDays);
        requireExecutor(executor);
        this.name = newName;
        this.assetId = newAssetId;
        this.frequencyHours = newFrequencyHours;
        this.rpoHours = newRpoHours;
        this.rtoMinutes = newRtoMinutes;
        this.retentionDays = newRetentionDays;
        this.restoreTestEveryDays = newRestoreTestEveryDays;
        return record("UPDATED", executor, this.status, this.status, "Policy updated.");
    }

    /** Behavior Qualifier: {@code control/pause}. ACTIVE -&gt; PAUSED: judged by nobody until it is resumed (planned work, a retired source). */
    public PolicyAuditEntry pause(String executor) {
        requireStatus(PolicyStatus.ACTIVE);
        requireExecutor(executor);
        this.status = PolicyStatus.PAUSED;
        return record("PAUSED", executor, PolicyStatus.ACTIVE, PolicyStatus.PAUSED, "Paused: the policy is no longer judged.");
    }

    /** Behavior Qualifier: {@code control/resume}. PAUSED -&gt; ACTIVE. */
    public PolicyAuditEntry resume(String executor) {
        requireStatus(PolicyStatus.PAUSED);
        requireExecutor(executor);
        this.status = PolicyStatus.ACTIVE;
        return record("RESUMED", executor, PolicyStatus.PAUSED, PolicyStatus.ACTIVE, "Resumed.");
    }

    /**
     * How well the promise holds at that moment (ADR-031). The recovery point is the last successful backup, or the creation of the policy while
     * there is none yet: the data is protected until that plus the RPO. A restore test is due {@code restoreTestEveryDays} after the last
     * successful one, or after the creation. A paused policy is not judged.
     */
    public Assessment assess(Instant now) {
        if (status == PolicyStatus.PAUSED) {
            return new Assessment(Protection.PAUSED, null, RestoreTestStatus.NOT_REQUIRED, null, restoreMet());
        }
        Instant protectedUntil = (lastSuccessAt != null ? lastSuccessAt : createdAt).plus(Duration.ofHours(rpoHours));
        Protection protection;
        if (now.isAfter(protectedUntil)) {
            protection = Protection.RPO_BREACHED;
        } else {
            protection = lastSuccessAt != null ? Protection.OK : Protection.NEVER;
        }
        Instant restoreDue = null;
        RestoreTestStatus restoreStatus = RestoreTestStatus.NOT_REQUIRED;
        if (restoreTestEveryDays != null) {
            restoreDue = (lastRestoreTestAt != null ? lastRestoreTestAt : createdAt).plus(Duration.ofDays(restoreTestEveryDays));
            restoreStatus = now.isAfter(restoreDue) ? RestoreTestStatus.OVERDUE : RestoreTestStatus.OK;
        }
        return new Assessment(protection, protectedUntil, restoreStatus, restoreDue, restoreMet());
    }

    /** Did the last successful restore test fit in the recovery time objective? Unknown (null) until there is one. */
    private Boolean restoreMet() {
        return lastRestoreMinutes == null ? null : lastRestoreMinutes <= rtoMinutes;
    }

    // --- Internal helpers ---

    private PolicyAuditEntry record(String action, String executor, PolicyStatus from, PolicyStatus to, String detail) {
        this.updatedAt = Instant.now();
        PolicyAuditEntry entry = new PolicyAuditEntry(this.updatedAt, action, executor, from, to, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private void requireStatus(PolicyStatus expected) {
        if (this.status != expected) {
            throw new InvalidBackupPolicyStatusException(String.format("Illegal transition: BackupPolicy is [%s], expected [%s].", this.status, expected));
        }
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable BackupPolicy mutations.");
        }
    }

    private static void validate(String name, UUID assetId, int frequencyHours, int rpoHours, int rtoMinutes, int retentionDays, Integer restoreTestEveryDays) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Name is mandatory for a BackupPolicy (up to " + MAX_NAME_LENGTH + " characters).");
        }
        if (assetId == null) {
            throw new IllegalArgumentException("The asset the policy protects is mandatory.");
        }
        if (frequencyHours < 1 || frequencyHours > MAX_HOURS) {
            throw new IllegalArgumentException("The backup frequency must be between 1 and " + MAX_HOURS + " hours.");
        }
        if (rpoHours < 1 || rpoHours > MAX_HOURS) {
            throw new IllegalArgumentException("The recovery point objective must be between 1 and " + MAX_HOURS + " hours.");
        }
        if (rpoHours < frequencyHours) {
            throw new IllegalArgumentException("The recovery point objective cannot be shorter than the backup frequency: a backup every "
                    + frequencyHours + " hours cannot promise to lose less than that.");
        }
        if (rtoMinutes < 1 || rtoMinutes > MAX_RTO_MINUTES) {
            throw new IllegalArgumentException("The recovery time objective must be between 1 and " + MAX_RTO_MINUTES + " minutes.");
        }
        if (retentionDays < 1 || retentionDays > MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException("The retention must be between 1 and " + MAX_RETENTION_DAYS + " days.");
        }
        if (restoreTestEveryDays != null && (restoreTestEveryDays < 1 || restoreTestEveryDays > MAX_RESTORE_TEST_DAYS)) {
            throw new IllegalArgumentException("The restore test interval must be between 1 and " + MAX_RESTORE_TEST_DAYS + " days, or left out.");
        }
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public String getName() { return name; }
    public UUID getAssetId() { return assetId; }
    public int getFrequencyHours() { return frequencyHours; }
    public int getRpoHours() { return rpoHours; }
    public int getRtoMinutes() { return rtoMinutes; }
    public int getRetentionDays() { return retentionDays; }
    public Integer getRestoreTestEveryDays() { return restoreTestEveryDays; }
    public PolicyStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getLastRunAt() { return lastRunAt; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public Instant getLastRestoreTestAt() { return lastRestoreTestAt; }
    public Integer getLastRestoreMinutes() { return lastRestoreMinutes; }
    public List<PolicyAuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    // --- Nested Value Objects ---

    public enum PolicyStatus { ACTIVE, PAUSED }

    /** PAUSED is not judged; NEVER is still within the RPO of the policy's creation with no backup yet; OK has a backup inside the RPO; RPO_BREACHED has none. */
    public enum Protection { PAUSED, NEVER, OK, RPO_BREACHED }

    public enum RestoreTestStatus { NOT_REQUIRED, OK, OVERDUE }

    public record PolicyAuditEntry(Instant occurredAt, String action, String executor, PolicyStatus fromStatus, PolicyStatus toStatus, String detail) {}

    /** The judgment of one moment: nothing of it is stored. {@code protectedUntil} and {@code restoreTestDueAt} are null when they do not apply; {@code rtoMet} is null until a restore was tested. */
    public record Assessment(Protection protection, Instant protectedUntil, RestoreTestStatus restoreTest, Instant restoreTestDueAt, Boolean rtoMet) {}
}
