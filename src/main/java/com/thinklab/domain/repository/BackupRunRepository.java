package com.thinklab.domain.repository;

import com.thinklab.domain.model.BackupRun;
import com.thinklab.domain.model.BackupRun.Outcome;
import com.thinklab.domain.model.BackupRun.RunKind;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/** Outbound Port for BackupRun persistence: runs are facts, inserted once and never changed. Every lookup is tenant-scoped. */
public interface BackupRunRepository {

    /**
     * Inserts the run. The same policy, kind and start is the same run (a unique index): the loser of a repeated or simultaneous report gets the
     * stored one back and {@code created} false, so a tool may retry a report as often as it likes.
     */
    Mono<Stored> create(BackupRun run);

    /** The run already reported for that policy, kind and start, if any. */
    Mono<BackupRun> findByNaturalKey(UUID organisationId, UUID policyId, RunKind kind, Instant startedAt);

    Mono<BackupRun> findById(UUID id, UUID organisationId);

    /** The tenant's runs, newest start first, at most {@code limit}. */
    Flux<BackupRun> findAll(UUID organisationId, Filter filter, int limit);

    record Stored(BackupRun run, boolean created) {
    }

    record Filter(UUID policyId, RunKind kind, Outcome outcome) {
    }
}
