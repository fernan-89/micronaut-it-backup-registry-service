package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.BackupRunResponse;
import com.thinklab.application.mapper.BackupMapper;
import com.thinklab.domain.repository.BackupRunRepository;
import com.thinklab.domain.repository.BackupRunRepository.Filter;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for listing the tenant's runs, newest start first, filterable by policy, kind and outcome, at most {@value #MAX_LIMIT} (BIAN Behavior Qualifier: {@code run/retrieve}, collection). */
@Singleton
public class RetrieveBackupRunsUseCase {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private static final Logger log = LoggerFactory.getLogger(RetrieveBackupRunsUseCase.class);

    private final BackupRunRepository repository;

    public RetrieveBackupRunsUseCase(BackupRunRepository repository) {
        this.repository = repository;
    }

    public Flux<BackupRunResponse> execute(UUID organisationId, Filter filter, Integer limit, String role) {
        log.info("[USE CASE] Retrieving the BackupRuns of organisation: {}", organisationId);

        return Mono.fromRunnable(() -> BackupAccess.requireStaff(role, "list backup runs"))
                .then(Mono.fromCallable(() -> effectiveLimit(limit)))
                .flatMapMany(effective -> repository.findAll(organisationId, filter, effective))
                .map(BackupMapper::toResponse);
    }

    private static int effectiveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("The limit must be between 1 and " + MAX_LIMIT + ".");
        }
        return limit;
    }
}
