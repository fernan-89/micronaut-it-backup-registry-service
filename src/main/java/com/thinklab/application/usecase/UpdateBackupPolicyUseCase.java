package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.UpdateBackupPolicyRequest;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for changing a policy (BIAN Behavior Qualifier: {@code update}). */
@Singleton
public class UpdateBackupPolicyUseCase {

    private static final Logger log = LoggerFactory.getLogger(UpdateBackupPolicyUseCase.class);

    private final BackupPolicyWorkflow workflow;

    public UpdateBackupPolicyUseCase(BackupPolicyWorkflow workflow) {
        this.workflow = workflow;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, UpdateBackupPolicyRequest request, String executor, String role) {
        log.info("[USE CASE] Updating BackupPolicy ID: {}", id);

        return workflow.apply(id, organisationId, role, "change a backup policy",
                policy -> policy.update(request.name(), request.assetId(), request.frequencyHours(), request.rpoHours(), request.rtoMinutes(), request.retentionDays(),
                        request.restoreTestEveryDays(), executor));
    }
}
