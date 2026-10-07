package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.mapper.BackupMapper;
import com.thinklab.domain.exception.BackupPolicyNotFoundException;
import com.thinklab.domain.repository.BackupPolicyRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Use Case for the forensic ledger of a policy (BIAN Behavior Qualifier: {@code audit-log/retrieve}). Staff only. */
@Singleton
public class RetrieveBackupPolicyAuditLogUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveBackupPolicyAuditLogUseCase.class);

    private final BackupPolicyRepository repository;

    public RetrieveBackupPolicyAuditLogUseCase(BackupPolicyRepository repository) {
        this.repository = repository;
    }

    public Mono<List<AuditEntryResponse>> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving the audit log of BackupPolicy ID: {}", id);

        return Mono.fromRunnable(() -> BackupAccess.requireStaff(role, "read the audit trail of a backup policy"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new BackupPolicyNotFoundException("BackupPolicy " + id + " not found.")))
                .map(policy -> policy.getAuditTrail().stream().map(BackupMapper::toResponse).collect(Collectors.toList()));
    }
}
