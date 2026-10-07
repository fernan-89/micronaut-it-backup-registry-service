package com.thinklab.application.mapper;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.BackupPolicyResponse;
import com.thinklab.application.dto.response.BackupRunResponse;
import com.thinklab.domain.model.BackupPolicy;
import com.thinklab.domain.model.BackupPolicy.Assessment;
import com.thinklab.domain.model.BackupPolicy.PolicyAuditEntry;
import com.thinklab.domain.model.BackupRun;

import java.time.Instant;

public final class BackupMapper {

    private BackupMapper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /** The policy as it stands, judged at {@code now}. */
    public static BackupPolicyResponse toResponse(BackupPolicy policy, Instant now) {
        Assessment judged = policy.assess(now);
        return new BackupPolicyResponse(policy.getId(), policy.getOrganisationId(), policy.getName(), policy.getAssetId(), policy.getFrequencyHours(), policy.getRpoHours(),
                policy.getRtoMinutes(), policy.getRetentionDays(), policy.getRestoreTestEveryDays(), policy.getStatus().name(), judged.protection().name(), judged.protectedUntil(),
                judged.restoreTest().name(), judged.restoreTestDueAt(), judged.rtoMet(), policy.getLastRunAt(), policy.getLastSuccessAt(), policy.getLastRestoreTestAt(),
                policy.getLastRestoreMinutes(), policy.getCreatedAt(), policy.getUpdatedAt());
    }

    public static BackupRunResponse toResponse(BackupRun run) {
        return new BackupRunResponse(run.getId(), run.getOrganisationId(), run.getPolicyId(), run.getKind().name(), run.getOutcome().name(), run.getStartedAt(), run.getFinishedAt(),
                run.durationMinutes(), run.getSizeBytes(), run.getFailureReason() != null ? run.getFailureReason().name() : null, run.getReportedBy(), run.getReportedAt());
    }

    public static AuditEntryResponse toResponse(PolicyAuditEntry entry) {
        return new AuditEntryResponse(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus() != null ? entry.fromStatus().name() : null,
                entry.toStatus().name(), entry.detail());
    }
}
