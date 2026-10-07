package com.thinklab.application.dto.request;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * DTO for {@code update}: the whole definition again. The recovery point objective cannot be shorter than the backup frequency (the
 * domain refuses it with a 400 that says why); {@code restoreTestEveryDays} left out means no restore test is required.
 */
@Serdeable
public record UpdateBackupPolicyRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 80, message = "Name must not exceed 80 characters")
        String name,
        @NotNull(message = "The asset the policy protects is required") UUID assetId,
        @NotNull(message = "The backup frequency is required") @Min(value = 1, message = "Frequency must be at least 1 hour") @Max(value = 8760, message = "Frequency must not exceed 8760 hours")
        Integer frequencyHours,
        @NotNull(message = "The recovery point objective is required") @Min(value = 1, message = "RPO must be at least 1 hour") @Max(value = 8760, message = "RPO must not exceed 8760 hours")
        Integer rpoHours,
        @NotNull(message = "The recovery time objective is required") @Min(value = 1, message = "RTO must be at least 1 minute") @Max(value = 43200, message = "RTO must not exceed 43200 minutes")
        Integer rtoMinutes,
        @NotNull(message = "The retention is required") @Min(value = 1, message = "Retention must be at least 1 day") @Max(value = 3650, message = "Retention must not exceed 3650 days")
        Integer retentionDays,
        @Nullable @Min(value = 1, message = "The restore test interval must be at least 1 day") @Max(value = 365, message = "The restore test interval must not exceed 365 days")
        Integer restoreTestEveryDays
) {}
