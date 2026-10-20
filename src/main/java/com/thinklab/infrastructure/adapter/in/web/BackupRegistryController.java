package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.InitiateBackupPolicyRequest;
import com.thinklab.application.dto.request.ReportBackupRunRequest;
import com.thinklab.application.dto.request.UpdateBackupPolicyRequest;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.BackupPolicyResponse;
import com.thinklab.application.dto.response.PromiseResponse;
import com.thinklab.application.dto.response.BackupRunResponse;
import com.thinklab.application.usecase.ControlBackupPolicyUseCase;
import com.thinklab.application.usecase.InitiateBackupPolicyUseCase;
import com.thinklab.application.usecase.ReportBackupRunUseCase;
import com.thinklab.application.usecase.RetrieveBackupPoliciesUseCase;
import com.thinklab.application.usecase.RetrieveBackupPolicyAuditLogUseCase;
import com.thinklab.application.usecase.RetrieveBackupPolicyUseCase;
import com.thinklab.application.usecase.RetrieveBackupRunUseCase;
import com.thinklab.application.usecase.RetrieveBackupRunsUseCase;
import com.thinklab.application.usecase.RetrievePromisesUseCase;
import com.thinklab.application.usecase.UpdateBackupPolicyUseCase;
import com.thinklab.domain.model.BackupPolicy.PolicyStatus;
import com.thinklab.domain.model.BackupPolicy.Protection;
import com.thinklab.domain.model.BackupRun.Outcome;
import com.thinklab.domain.model.BackupRun.RunKind;
import com.thinklab.domain.repository.BackupRunRepository;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Inbound Web Adapter for the {@code it-backup-registry} Service Domain.
 *
 * <p><b>BIAN-Aligned Resource Model (ADR-013):</b> {@link com.thinklab.domain.model.BackupPolicy} is the Control Record, at the root;
 * {@link com.thinklab.domain.model.BackupRun} is the secondary aggregate, under {@code /run}: facts the tool reports, never edited, never
 * deleted. There is no {@code DELETE}: a policy is paused.
 *
 * <p><b>Tenant on every route, staff only (ADR-032):</b> {@code X-Tenant-Id} scopes every lookup (another tenant's policy is a 404) and
 * {@code X-Role} makes every route refuse a {@code REQUESTER} with 403 {@code ERR-BKP-00403}.
 */
@Controller("/it-backup-registry/v1")
public class BackupRegistryController {

    private static final Logger log = LoggerFactory.getLogger(BackupRegistryController.class);
    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String EXECUTOR_HEADER = "X-Executor";
    static final String ROLE_HEADER = "X-Role";

    private final InitiateBackupPolicyUseCase initiatePolicy;
    private final RetrieveBackupPolicyUseCase retrievePolicy;
    private final RetrieveBackupPoliciesUseCase retrievePolicies;
    private final UpdateBackupPolicyUseCase updatePolicy;
    private final ControlBackupPolicyUseCase controlPolicy;
    private final RetrieveBackupPolicyAuditLogUseCase policyAuditLog;
    private final ReportBackupRunUseCase reportRun;
    private final RetrieveBackupRunUseCase retrieveRun;
    private final RetrieveBackupRunsUseCase retrieveRuns;
    private final RetrievePromisesUseCase retrievePromises;

    public BackupRegistryController(InitiateBackupPolicyUseCase initiatePolicy, RetrieveBackupPolicyUseCase retrievePolicy, RetrieveBackupPoliciesUseCase retrievePolicies,
                                    UpdateBackupPolicyUseCase updatePolicy, ControlBackupPolicyUseCase controlPolicy, RetrieveBackupPolicyAuditLogUseCase policyAuditLog,
                                    ReportBackupRunUseCase reportRun, RetrieveBackupRunUseCase retrieveRun, RetrieveBackupRunsUseCase retrieveRuns,
                                    RetrievePromisesUseCase retrievePromises) {
        this.initiatePolicy = initiatePolicy;
        this.retrievePolicy = retrievePolicy;
        this.retrievePolicies = retrievePolicies;
        this.updatePolicy = updatePolicy;
        this.controlPolicy = controlPolicy;
        this.policyAuditLog = policyAuditLog;
        this.reportRun = reportRun;
        this.retrieveRun = retrieveRun;
        this.retrieveRuns = retrieveRuns;
        this.retrievePromises = retrievePromises;
    }

    // ------------------------------------------------------------------ Policies

    /** Behavior Qualifier: {@code initiate}. What is promised about an asset. */
    @Post("/initiate")
    public Mono<HttpResponse<BackupPolicyResponse>> initiate(
            @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid InitiateBackupPolicyRequest request
    ) {
        log.info("[ACTION: INITIATE_BACKUP_POLICY] [EXECUTOR: {}] Received request for organisation: {}", executor, tenantId);

        return initiatePolicy.execute(UUID.fromString(tenantId), request, executor, role).map(HttpResponse::created);
    }

    /** Behavior Qualifier: {@code retrieve}. One policy of the tenant, judged now. */
    @Get("/{id}/retrieve")
    public Mono<HttpResponse<BackupPolicyResponse>> retrieve(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> retrievePolicy.execute(id, UUID.fromString(tenantId), role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code retrieve} (collection), oldest first, filterable by status, asset and protection. */
    @Get("/retrieve")
    public Mono<List<BackupPolicyResponse>> retrieveAll(@Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role,
                                                        @QueryValue @Nullable PolicyStatus status, @QueryValue @Nullable UUID assetId, @QueryValue @Nullable Protection protection) {
        return Mono.defer(() -> retrievePolicies.execute(UUID.fromString(tenantId), status, assetId, protection, role).collectList());
    }

    /**
     * Behavior Qualifier: {@code promises/retrieve} (platform ADR-001, the promises contract). Every broken promise and every one that changed within the days asked
     * (30 unless asked, 1 to 365), at most {@code limit} of each (500 unless asked, at most 1000).
     */
    @Get("/promises/retrieve")
    public Mono<List<PromiseResponse>> promises(@Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role,
                                                @QueryValue @Nullable Integer changedWithinDays, @QueryValue @Nullable Integer limit) {
        return Mono.defer(() -> retrievePromises.execute(UUID.fromString(tenantId), changedWithinDays, limit, role));
    }

    /** Behavior Qualifier: {@code update}. The whole definition again. */
    @Put("/{id}/update")
    public Mono<HttpResponse<Void>> update(
            @PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid UpdateBackupPolicyRequest request
    ) {
        return Mono.defer(() -> updatePolicy.execute(id, UUID.fromString(tenantId), request, executor, role)).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code control/pause}. ACTIVE -&gt; PAUSED: the policy is no longer judged. */
    @Put("/{id}/control/pause")
    public Mono<HttpResponse<Void>> pause(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                          @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        return control(id, tenantId, ControlBackupPolicyUseCase.Action.PAUSE, executor, role);
    }

    /** Behavior Qualifier: {@code control/resume}. PAUSED -&gt; ACTIVE. */
    @Put("/{id}/control/resume")
    public Mono<HttpResponse<Void>> resume(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                           @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        return control(id, tenantId, ControlBackupPolicyUseCase.Action.RESUME, executor, role);
    }

    /** Behavior Qualifier: {@code audit-log/retrieve}. */
    @Get("/{id}/audit-log/retrieve")
    public Mono<List<AuditEntryResponse>> auditLog(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> policyAuditLog.execute(id, UUID.fromString(tenantId), role));
    }

    // ------------------------------------------------------------------ Runs

    /** Behavior Qualifier: {@code run/initiate}. What the tool reports when a backup or a restore test ends: 201 for a new run, 200 for one already reported. */
    @Post("/run/initiate")
    public Mono<HttpResponse<BackupRunResponse>> reportRun(
            @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid ReportBackupRunRequest request
    ) {
        log.info("[ACTION: REPORT_BACKUP_RUN] [EXECUTOR: {}] Received request for organisation: {}", executor, tenantId);

        return reportRun.execute(UUID.fromString(tenantId), request, executor, role)
                .map(reported -> reported.created() ? HttpResponse.created(reported.run()) : HttpResponse.ok(reported.run()));
    }

    /** Behavior Qualifier: {@code run/retrieve}. One run of the tenant. */
    @Get("/run/{id}/retrieve")
    public Mono<HttpResponse<BackupRunResponse>> retrieveRun(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> retrieveRun.execute(id, UUID.fromString(tenantId), role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code run/retrieve} (collection), newest start first, at most 200 (50 unless asked). */
    @Get("/run/retrieve")
    public Mono<List<BackupRunResponse>> retrieveRuns(@Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role,
                                                      @QueryValue @Nullable UUID policyId, @QueryValue @Nullable RunKind kind, @QueryValue @Nullable Outcome outcome,
                                                      @QueryValue @Nullable Integer limit) {
        return Mono.defer(() -> retrieveRuns.execute(UUID.fromString(tenantId), new BackupRunRepository.Filter(policyId, kind, outcome), limit, role).collectList());
    }

    private Mono<HttpResponse<Void>> control(UUID id, String tenantId, ControlBackupPolicyUseCase.Action action, String executor, String role) {
        log.info("[ACTION: {}_BACKUP_POLICY] [EXECUTOR: {}] for ID: {}", action, executor, id);

        return Mono.defer(() -> controlPolicy.execute(id, UUID.fromString(tenantId), action, executor, role)).thenReturn(HttpResponse.noContent());
    }
}
