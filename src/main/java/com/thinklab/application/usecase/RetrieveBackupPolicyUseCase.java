package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.BackupPolicyResponse;
import com.thinklab.application.mapper.BackupMapper;
import com.thinklab.domain.exception.BackupPolicyNotFoundException;
import com.thinklab.domain.repository.BackupPolicyRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Use Case for reading one policy of the tenant, judged now (BIAN Behavior Qualifier: {@code retrieve}). Staff only; another tenant is a 404. */
@Singleton
public class RetrieveBackupPolicyUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveBackupPolicyUseCase.class);

    private final BackupPolicyRepository repository;
    private final Clock clock;

    public RetrieveBackupPolicyUseCase(BackupPolicyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Mono<BackupPolicyResponse> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving BackupPolicy ID: {}", id);

        return Mono.fromRunnable(() -> BackupAccess.requireStaff(role, "read a backup policy"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new BackupPolicyNotFoundException("BackupPolicy " + id + " not found.")))
                .map(policy -> BackupMapper.toResponse(policy, Instant.now(clock)));
    }
}
