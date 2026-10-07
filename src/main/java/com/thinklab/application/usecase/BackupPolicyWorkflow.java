package com.thinklab.application.usecase;

import com.thinklab.domain.exception.BackupPolicyNotFoundException;
import com.thinklab.domain.model.BackupPolicy;
import com.thinklab.domain.model.BackupPolicy.PolicyAuditEntry;
import com.thinklab.domain.repository.BackupPolicyRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.Function;

/** The shape every staff change to a policy shares: staff only, load (a 404 if it is not the tenant's), apply, save with the status loaded. */
@Singleton
public class BackupPolicyWorkflow {

    private final BackupPolicyRepository repository;

    public BackupPolicyWorkflow(BackupPolicyRepository repository) {
        this.repository = repository;
    }

    public Mono<Void> apply(UUID id, UUID organisationId, String role, String operation, Function<BackupPolicy, PolicyAuditEntry> action) {
        return Mono.fromRunnable(() -> BackupAccess.requireStaff(role, operation))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new BackupPolicyNotFoundException("BackupPolicy " + id + " not found.")))
                .flatMap(policy -> {
                    var statusBefore = policy.getStatus();
                    PolicyAuditEntry entry = action.apply(policy);
                    return repository.save(policy, statusBefore, entry);
                });
    }
}
