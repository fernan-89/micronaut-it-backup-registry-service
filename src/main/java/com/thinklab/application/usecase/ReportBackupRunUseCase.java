package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.ReportBackupRunRequest;
import com.thinklab.application.dto.response.BackupRunResponse;
import com.thinklab.application.mapper.BackupMapper;
import com.thinklab.domain.exception.BackupPolicyNotFoundException;
import com.thinklab.domain.model.BackupRun;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.BackupPolicyRepository;
import com.thinklab.domain.repository.BackupRunRepository;
import com.thinklab.domain.repository.BackupRunRepository.Stored;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Use Case for what the tool reports when a backup or a restore test ends (BIAN Behavior Qualifier: {@code run/initiate}), ADR-030.
 * Idempotent: the same policy, kind and start is the same run, so a tool may retry; the stored run comes back with {@code created} false.
 * Either way the policy's summary is folded again (it only moves forward in time), so a report that stored the run and died before the
 * summary was updated is repaired by the retry. A sovereign id is spent only for a run that is new.
 */
@Singleton
public class ReportBackupRunUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReportBackupRunUseCase.class);

    /** The run as stored, and whether this report created it (201) or found it already there (200). */
    public record Reported(BackupRunResponse run, boolean created) {
    }

    private final HashServicePort hashServicePort;
    private final BackupPolicyRepository policies;
    private final BackupRunRepository runs;
    private final Clock clock;

    public ReportBackupRunUseCase(HashServicePort hashServicePort, BackupPolicyRepository policies, BackupRunRepository runs, Clock clock) {
        this.hashServicePort = hashServicePort;
        this.policies = policies;
        this.runs = runs;
        this.clock = clock;
    }

    public Mono<Reported> execute(UUID organisationId, ReportBackupRunRequest request, String executor, String role) {
        log.info("[USE CASE] Reporting a {} {} for BackupPolicy ID: {}", request.kind(), request.outcome(), request.policyId());

        return Mono.fromRunnable(() -> BackupAccess.requireStaff(role, "report a backup run"))
                .then(Mono.defer(() -> policies.findById(request.policyId(), organisationId)))
                .switchIfEmpty(Mono.error(new BackupPolicyNotFoundException("BackupPolicy " + request.policyId() + " not found.")))
                .flatMap(policy -> runs.findByNaturalKey(organisationId, policy.getId(), request.kind(), BackupRun.truncate(request.startedAt()))
                        .map(existing -> new Stored(existing, false))
                        .switchIfEmpty(Mono.defer(() -> store(organisationId, request, executor))))
                .flatMap(stored -> policies.recordRun(request.policyId(), organisationId, stored.run())
                        .thenReturn(new Reported(BackupMapper.toResponse(stored.run()), stored.created())));
    }

    private Mono<Stored> store(UUID organisationId, ReportBackupRunRequest request, String executor) {
        return hashServicePort.generateSovereignId("backup-run-creation")
                .map(id -> BackupRun.createNew(id, organisationId, request.policyId(), request.kind(), request.outcome(), request.startedAt(), request.finishedAt(),
                        request.sizeBytes(), request.failureReason(), executor, Instant.now(clock)))
                .flatMap(runs::create);
    }
}
