package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.BackupPolicyResponse;
import com.thinklab.application.mapper.BackupMapper;
import com.thinklab.domain.model.BackupPolicy.PolicyStatus;
import com.thinklab.domain.model.BackupPolicy.Protection;
import com.thinklab.domain.repository.BackupPolicyRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Use Case for listing the tenant's policies, oldest first, judged now, filterable by status, asset and protection (BIAN Behavior Qualifier:
 * {@code retrieve}, collection). The protection is judged when read, so it is filtered in memory over the tenant's policies (ADR-031).
 */
@Singleton
public class RetrieveBackupPoliciesUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveBackupPoliciesUseCase.class);

    private final BackupPolicyRepository repository;
    private final Clock clock;

    public RetrieveBackupPoliciesUseCase(BackupPolicyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Flux<BackupPolicyResponse> execute(UUID organisationId, PolicyStatus status, UUID assetId, Protection protection, String role) {
        log.info("[USE CASE] Retrieving the BackupPolicies of organisation: {}", organisationId);

        return Mono.fromRunnable(() -> BackupAccess.requireStaff(role, "list backup policies"))
                .thenMany(Flux.defer(() -> {
                    Instant now = Instant.now(clock);
                    return repository.findAll(organisationId)
                            .filter(policy -> status == null || policy.getStatus() == status)
                            .filter(policy -> assetId == null || assetId.equals(policy.getAssetId()))
                            .filter(policy -> protection == null || policy.assess(now).protection() == protection)
                            .map(policy -> BackupMapper.toResponse(policy, now));
                }));
    }
}
