package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.PromiseResponse;
import com.thinklab.domain.model.BackupPolicy;
import com.thinklab.domain.model.Promise;
import com.thinklab.domain.repository.BackupPolicyRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Use Case for the promises of the tenant's policies, judged now (BIAN Behavior Qualifier: {@code promises/retrieve}, platform ADR-001): a policy whose
 * backup is outside its RPO, or whose restore test is overdue, is broken (a fixed code each); one with a backup inside its RPO is kept; the rest are not
 * judged. Staff only.
 */
@Singleton
public class RetrievePromisesUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrievePromisesUseCase.class);

    private final BackupPolicyRepository repository;
    private final Clock clock;

    public RetrievePromisesUseCase(BackupPolicyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Mono<List<PromiseResponse>> execute(UUID organisationId, Integer changedWithinDays, Integer limit, String role) {
        log.info("[USE CASE] Retrieving the promises of the BackupPolicies of organisation: {}", organisationId);
        return Mono.fromRunnable(() -> BackupAccess.requireStaff(role, "list the promises of backup policies"))
                .then(Mono.fromCallable(() -> new int[]{PromiseSelection.days(changedWithinDays), PromiseSelection.limit(limit)}))
                .flatMap(window -> {
                    Instant now = Instant.now(clock);
                    return repository.findAll(organisationId).map(policy -> toResponse(policy, now)).collectList()
                            .map(all -> PromiseSelection.select(all, now.minus(Duration.ofDays(window[0])), window[1]));
                });
    }

    private static PromiseResponse toResponse(BackupPolicy policy, Instant now) {
        Promise promise = policy.promise(now);
        return new PromiseResponse(policy.getId(), policy.getName(), policy.getAssetId(), promise.verdict().name(), promise.code(), policy.getStatus().name(), policy.changedAt());
    }
}
