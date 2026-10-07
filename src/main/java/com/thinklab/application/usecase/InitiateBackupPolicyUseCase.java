package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateBackupPolicyRequest;
import com.thinklab.application.dto.response.BackupPolicyResponse;
import com.thinklab.application.mapper.BackupMapper;
import com.thinklab.domain.model.BackupPolicy;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.BackupPolicyRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Use Case for promising something about an asset (BIAN Behavior Qualifier: {@code initiate}). Staff only. */
@Singleton
public class InitiateBackupPolicyUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiateBackupPolicyUseCase.class);

    private final HashServicePort hashServicePort;
    private final BackupPolicyRepository repository;
    private final Clock clock;

    public InitiateBackupPolicyUseCase(HashServicePort hashServicePort, BackupPolicyRepository repository, Clock clock) {
        this.hashServicePort = hashServicePort;
        this.repository = repository;
        this.clock = clock;
    }

    public Mono<BackupPolicyResponse> execute(UUID organisationId, InitiateBackupPolicyRequest request, String executor, String role) {
        log.info("[USE CASE] Creating the BackupPolicy [{}] for organisation: {}", request.name(), organisationId);

        return Mono.fromRunnable(() -> BackupAccess.requireStaff(role, "create a backup policy"))
                .then(Mono.defer(() -> hashServicePort.generateSovereignId("backup-policy-creation")))
                .map(id -> BackupPolicy.createNew(id, organisationId, request.name(), request.assetId(), request.frequencyHours(), request.rpoHours(), request.rtoMinutes(),
                        request.retentionDays(), request.restoreTestEveryDays(), executor))
                .flatMap(repository::create)
                .map(policy -> BackupMapper.toResponse(policy, Instant.now(clock)));
    }
}
