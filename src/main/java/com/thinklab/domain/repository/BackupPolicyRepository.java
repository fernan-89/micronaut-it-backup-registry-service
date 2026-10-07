package com.thinklab.domain.repository;

import com.thinklab.domain.model.BackupPolicy;
import com.thinklab.domain.model.BackupPolicy.PolicyAuditEntry;
import com.thinklab.domain.model.BackupPolicy.PolicyStatus;
import com.thinklab.domain.model.BackupRun;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Outbound Port for BackupPolicy persistence. {@link #save} is a guarded write on the status loaded (ADR-033); every lookup is tenant-scoped. */
public interface BackupPolicyRepository {

    /** Inserts the policy. A name already used in the organisation is {@link com.thinklab.domain.exception.DuplicateBackupPolicyException}: the unique index is the atomic backstop. */
    Mono<BackupPolicy> create(BackupPolicy policy);

    Mono<BackupPolicy> findById(UUID id, UUID organisationId);

    /** The tenant's policies, oldest first. */
    Flux<BackupPolicy> findAll(UUID organisationId);

    Mono<Void> save(BackupPolicy policy, PolicyStatus expectedStatus, PolicyAuditEntry auditEntry);

    /**
     * Folds a reported run into the summary the policy keeps (ADR-030): the last run, the last successful backup, and the last successful restore
     * test with how long it took. Every part only moves forward in time, so reports that arrive late or twice change nothing they should not.
     */
    Mono<Void> recordRun(UUID policyId, UUID organisationId, BackupRun run);
}
