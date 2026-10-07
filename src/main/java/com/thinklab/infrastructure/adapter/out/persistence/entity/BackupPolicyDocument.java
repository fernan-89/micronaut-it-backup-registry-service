package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.BackupPolicy;
import com.thinklab.domain.model.BackupPolicy.PolicyAuditEntry;
import com.thinklab.domain.model.BackupPolicy.PolicyStatus;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Infrastructure-specific representation of the BackupPolicy Aggregate for MongoDB. */
@Introspected
public class BackupPolicyDocument {

    @BsonId
    private UUID id;

    private UUID organisationId;
    private String name;
    private UUID assetId;
    private int frequencyHours;
    private int rpoHours;
    private int rtoMinutes;
    private int retentionDays;
    private Integer restoreTestEveryDays;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastRunAt;
    private Instant lastSuccessAt;
    private Instant lastRestoreTestAt;
    private Integer lastRestoreMinutes;
    private List<AuditEntryDocument> auditTrail = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }
    public int getFrequencyHours() { return frequencyHours; }
    public void setFrequencyHours(int frequencyHours) { this.frequencyHours = frequencyHours; }
    public int getRpoHours() { return rpoHours; }
    public void setRpoHours(int rpoHours) { this.rpoHours = rpoHours; }
    public int getRtoMinutes() { return rtoMinutes; }
    public void setRtoMinutes(int rtoMinutes) { this.rtoMinutes = rtoMinutes; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    public Integer getRestoreTestEveryDays() { return restoreTestEveryDays; }
    public void setRestoreTestEveryDays(Integer restoreTestEveryDays) { this.restoreTestEveryDays = restoreTestEveryDays; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(Instant lastRunAt) { this.lastRunAt = lastRunAt; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public void setLastSuccessAt(Instant lastSuccessAt) { this.lastSuccessAt = lastSuccessAt; }
    public Instant getLastRestoreTestAt() { return lastRestoreTestAt; }
    public void setLastRestoreTestAt(Instant lastRestoreTestAt) { this.lastRestoreTestAt = lastRestoreTestAt; }
    public Integer getLastRestoreMinutes() { return lastRestoreMinutes; }
    public void setLastRestoreMinutes(Integer lastRestoreMinutes) { this.lastRestoreMinutes = lastRestoreMinutes; }
    public List<AuditEntryDocument> getAuditTrail() { return auditTrail; }
    public void setAuditTrail(List<AuditEntryDocument> auditTrail) { this.auditTrail = auditTrail; }

    @Introspected
    public record AuditEntryDocument(Instant occurredAt, String action, String executor, String fromStatus, String toStatus, String detail) {

        public static AuditEntryDocument fromDomain(PolicyAuditEntry entry) {
            return new AuditEntryDocument(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus() != null ? entry.fromStatus().name() : null,
                    entry.toStatus().name(), entry.detail());
        }

        // toStatus has no null branch: fromDomain always writes entry.toStatus().name().
        PolicyAuditEntry toDomain() {
            return new PolicyAuditEntry(occurredAt, action, executor, fromStatus != null ? PolicyStatus.valueOf(fromStatus) : null, PolicyStatus.valueOf(toStatus), detail);
        }
    }

    public static final class BackupPolicyPersistenceMapper {

        private BackupPolicyPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static BackupPolicyDocument toDocument(BackupPolicy policy) {
            BackupPolicyDocument doc = new BackupPolicyDocument();
            doc.setId(policy.getId());
            doc.setOrganisationId(policy.getOrganisationId());
            doc.setName(policy.getName());
            doc.setAssetId(policy.getAssetId());
            doc.setFrequencyHours(policy.getFrequencyHours());
            doc.setRpoHours(policy.getRpoHours());
            doc.setRtoMinutes(policy.getRtoMinutes());
            doc.setRetentionDays(policy.getRetentionDays());
            doc.setRestoreTestEveryDays(policy.getRestoreTestEveryDays());
            doc.setStatus(policy.getStatus().name());
            doc.setCreatedAt(policy.getCreatedAt());
            doc.setUpdatedAt(policy.getUpdatedAt());
            doc.setLastRunAt(policy.getLastRunAt());
            doc.setLastSuccessAt(policy.getLastSuccessAt());
            doc.setLastRestoreTestAt(policy.getLastRestoreTestAt());
            doc.setLastRestoreMinutes(policy.getLastRestoreMinutes());
            doc.setAuditTrail(policy.getAuditTrail().stream().map(AuditEntryDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static BackupPolicy toDomain(BackupPolicyDocument doc) {
            return BackupPolicy.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getName(), doc.getAssetId(), doc.getFrequencyHours(), doc.getRpoHours(),
                    doc.getRtoMinutes(), doc.getRetentionDays(), doc.getRestoreTestEveryDays(), PolicyStatus.valueOf(doc.getStatus()), doc.getCreatedAt(), doc.getUpdatedAt(),
                    doc.getLastRunAt(), doc.getLastSuccessAt(), doc.getLastRestoreTestAt(), doc.getLastRestoreMinutes(),
                    doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).collect(Collectors.toList()));
        }
    }
}
