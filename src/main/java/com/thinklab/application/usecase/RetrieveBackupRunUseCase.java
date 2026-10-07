package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.BackupRunResponse;
import com.thinklab.application.mapper.BackupMapper;
import com.thinklab.domain.exception.BackupRunNotFoundException;
import com.thinklab.domain.repository.BackupRunRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for reading one run of the tenant (BIAN Behavior Qualifier: {@code run/retrieve}). Staff only; another tenant is a 404. */
@Singleton
public class RetrieveBackupRunUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveBackupRunUseCase.class);

    private final BackupRunRepository repository;

    public RetrieveBackupRunUseCase(BackupRunRepository repository) {
        this.repository = repository;
    }

    public Mono<BackupRunResponse> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving BackupRun ID: {}", id);

        return Mono.fromRunnable(() -> BackupAccess.requireStaff(role, "read a backup run"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new BackupRunNotFoundException("BackupRun " + id + " not found.")))
                .map(BackupMapper::toResponse);
    }
}
