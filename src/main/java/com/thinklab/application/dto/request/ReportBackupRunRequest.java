package com.thinklab.application.dto.request;

import com.thinklab.domain.model.BackupRun.FailureReason;
import com.thinklab.domain.model.BackupRun.Outcome;
import com.thinklab.domain.model.BackupRun.RunKind;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for {@code run/initiate}: what the tool reports when a backup or a restore test ends. A failed run names one reason from a fixed list
 * (never the tool's own text); a successful one names none. Reporting the same policy, kind and start again answers the run already stored.
 */
@Serdeable
public record ReportBackupRunRequest(
        @NotNull(message = "The policy is required") UUID policyId,
        @NotNull(message = "The kind of run is required") RunKind kind,
        @NotNull(message = "The outcome is required") Outcome outcome,
        @NotNull(message = "When the run started is required") Instant startedAt,
        @NotNull(message = "When the run finished is required") Instant finishedAt,
        @Nullable @PositiveOrZero(message = "The size cannot be negative") Long sizeBytes,
        @Nullable FailureReason failureReason
) {}
