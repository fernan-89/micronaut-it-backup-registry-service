package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.BackupRun;
import com.thinklab.domain.model.BackupRun.FailureReason;
import com.thinklab.domain.model.BackupRun.Outcome;
import com.thinklab.domain.model.BackupRun.RunKind;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/** Infrastructure-specific representation of the BackupRun Aggregate for MongoDB. Immutable facts: only ever inserted. */
@Introspected
public class BackupRunDocument {

    @BsonId
    private UUID id;

    private UUID organisationId;
    private UUID policyId;
    private String kind;
    private String outcome;
    private Instant startedAt;
    private Instant finishedAt;
    private Long sizeBytes;
    private String failureReason;
    private String reportedBy;
    private Instant reportedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public UUID getPolicyId() { return policyId; }
    public void setPolicyId(UUID policyId) { this.policyId = policyId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public String getReportedBy() { return reportedBy; }
    public void setReportedBy(String reportedBy) { this.reportedBy = reportedBy; }
    public Instant getReportedAt() { return reportedAt; }
    public void setReportedAt(Instant reportedAt) { this.reportedAt = reportedAt; }

    public static final class BackupRunPersistenceMapper {

        private BackupRunPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static BackupRunDocument toDocument(BackupRun run) {
            BackupRunDocument doc = new BackupRunDocument();
            doc.setId(run.getId());
            doc.setOrganisationId(run.getOrganisationId());
            doc.setPolicyId(run.getPolicyId());
            doc.setKind(run.getKind().name());
            doc.setOutcome(run.getOutcome().name());
            doc.setStartedAt(run.getStartedAt());
            doc.setFinishedAt(run.getFinishedAt());
            doc.setSizeBytes(run.getSizeBytes());
            doc.setFailureReason(run.getFailureReason() != null ? run.getFailureReason().name() : null);
            doc.setReportedBy(run.getReportedBy());
            doc.setReportedAt(run.getReportedAt());
            return doc;
        }

        public static BackupRun toDomain(BackupRunDocument doc) {
            return BackupRun.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getPolicyId(), RunKind.valueOf(doc.getKind()), Outcome.valueOf(doc.getOutcome()),
                    doc.getStartedAt(), doc.getFinishedAt(), doc.getSizeBytes(), doc.getFailureReason() != null ? FailureReason.valueOf(doc.getFailureReason()) : null,
                    doc.getReportedBy(), doc.getReportedAt());
        }
    }
}
