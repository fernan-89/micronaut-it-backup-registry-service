package com.thinklab.application.dto.response;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

/** A policy and how its promise holds right now: {@code protection}, {@code restoreTest} and {@code rtoMet} are judged when read, never stored. */
@Serdeable
public record BackupPolicyResponse(UUID id, UUID organisationId, String name, UUID assetId, int frequencyHours, int rpoHours, int rtoMinutes, int retentionDays,
                                   @Nullable Integer restoreTestEveryDays, String status, String protection, @Nullable Instant protectedUntil, String restoreTest,
                                   @Nullable Instant restoreTestDueAt, @Nullable Boolean rtoMet, @Nullable Instant lastRunAt, @Nullable Instant lastSuccessAt,
                                   @Nullable Instant lastRestoreTestAt, @Nullable Integer lastRestoreMinutes, Instant createdAt, Instant updatedAt) {}
