package com.thinklab.application.dto.response;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

@Serdeable
public record BackupRunResponse(UUID id, UUID organisationId, UUID policyId, String kind, String outcome, Instant startedAt, Instant finishedAt, int durationMinutes,
                                @Nullable Long sizeBytes, @Nullable String failureReason, String reportedBy, Instant reportedAt) {}
