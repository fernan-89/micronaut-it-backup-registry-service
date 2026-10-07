package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.InitiateBackupPolicyRequest;
import com.thinklab.application.dto.request.ReportBackupRunRequest;
import com.thinklab.application.dto.request.UpdateBackupPolicyRequest;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.BackupPolicyResponse;
import com.thinklab.application.dto.response.BackupRunResponse;
import com.thinklab.application.usecase.ControlBackupPolicyUseCase;
import com.thinklab.application.usecase.InitiateBackupPolicyUseCase;
import com.thinklab.application.usecase.ReportBackupRunUseCase;
import com.thinklab.application.usecase.ReportBackupRunUseCase.Reported;
import com.thinklab.application.usecase.RetrieveBackupPoliciesUseCase;
import com.thinklab.application.usecase.RetrieveBackupPolicyAuditLogUseCase;
import com.thinklab.application.usecase.RetrieveBackupPolicyUseCase;
import com.thinklab.application.usecase.RetrieveBackupRunUseCase;
import com.thinklab.application.usecase.RetrieveBackupRunsUseCase;
import com.thinklab.application.usecase.UpdateBackupPolicyUseCase;
import com.thinklab.domain.model.BackupPolicy.PolicyStatus;
import com.thinklab.domain.model.BackupPolicy.Protection;
import com.thinklab.domain.model.BackupRun.Outcome;
import com.thinklab.domain.model.BackupRun.RunKind;
import com.thinklab.domain.repository.BackupRunRepository.Filter;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/** The controller only reads headers and delegates: tenant, executor and role always travel to the use case. */
@ExtendWith(MockitoExtension.class)
class BackupRegistryControllerTest {

    private static final String EXECUTOR = "op-1";
    private static final String ROLE = "ENGINEER";
    private final UUID tenant = UUID.randomUUID();
    private final String tenantHeader = tenant.toString();
    private final UUID id = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-07T12:00:00Z");

    @Mock private InitiateBackupPolicyUseCase initiatePolicy;
    @Mock private RetrieveBackupPolicyUseCase retrievePolicy;
    @Mock private RetrieveBackupPoliciesUseCase retrievePolicies;
    @Mock private UpdateBackupPolicyUseCase updatePolicy;
    @Mock private ControlBackupPolicyUseCase controlPolicy;
    @Mock private RetrieveBackupPolicyAuditLogUseCase policyAuditLog;
    @Mock private ReportBackupRunUseCase reportRun;
    @Mock private RetrieveBackupRunUseCase retrieveRun;
    @Mock private RetrieveBackupRunsUseCase retrieveRuns;

    private BackupRegistryController controller() {
        return new BackupRegistryController(initiatePolicy, retrievePolicy, retrievePolicies, updatePolicy, controlPolicy, policyAuditLog, reportRun, retrieveRun, retrieveRuns);
    }

    private BackupPolicyResponse policyResponse() {
        return new BackupPolicyResponse(id, tenant, "Nightly", UUID.randomUUID(), 24, 48, 120, 30, null, "ACTIVE", "NEVER", now, "NOT_REQUIRED", null, null, null, null, null, null,
                now, now);
    }

    private BackupRunResponse runResponse() {
        return new BackupRunResponse(id, tenant, UUID.randomUUID(), "BACKUP", "SUCCEEDED", now, now, 1, null, null, "tool", now);
    }

    @Test
    @DisplayName("initiate answers 201 with the policy")
    void initiate() {
        InitiateBackupPolicyRequest request = new InitiateBackupPolicyRequest("Nightly", UUID.randomUUID(), 24, 48, 120, 30, null);
        when(initiatePolicy.execute(tenant, request, EXECUTOR, ROLE)).thenReturn(Mono.just(policyResponse()));

        StepVerifier.create(controller().initiate(tenantHeader, EXECUTOR, ROLE, request)).assertNext(response -> assertEquals(HttpStatus.CREATED, response.getStatus())).verifyComplete();
    }

    @Test
    @DisplayName("retrieve and the list answer the use cases' result with the filters")
    void retrieve() {
        when(retrievePolicy.execute(id, tenant, ROLE)).thenReturn(Mono.just(policyResponse()));
        when(retrievePolicies.execute(tenant, PolicyStatus.ACTIVE, null, Protection.OK, ROLE)).thenReturn(Flux.just(policyResponse()));

        StepVerifier.create(controller().retrieve(id, tenantHeader, ROLE)).assertNext(response -> assertEquals(HttpStatus.OK, response.getStatus())).verifyComplete();
        StepVerifier.create(controller().retrieveAll(tenantHeader, ROLE, PolicyStatus.ACTIVE, null, Protection.OK)).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
    }

    @Test
    @DisplayName("update, pause and resume answer 204 and pass the right action")
    void changes() {
        UpdateBackupPolicyRequest request = new UpdateBackupPolicyRequest("N", UUID.randomUUID(), 24, 48, 120, 30, null);
        when(updatePolicy.execute(id, tenant, request, EXECUTOR, ROLE)).thenReturn(Mono.empty());
        when(controlPolicy.execute(id, tenant, ControlBackupPolicyUseCase.Action.PAUSE, EXECUTOR, ROLE)).thenReturn(Mono.empty());
        when(controlPolicy.execute(id, tenant, ControlBackupPolicyUseCase.Action.RESUME, EXECUTOR, ROLE)).thenReturn(Mono.empty());

        StepVerifier.create(controller().update(id, tenantHeader, EXECUTOR, ROLE, request)).assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus())).verifyComplete();
        StepVerifier.create(controller().pause(id, tenantHeader, EXECUTOR, ROLE)).assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus())).verifyComplete();
        StepVerifier.create(controller().resume(id, tenantHeader, EXECUTOR, ROLE)).assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus())).verifyComplete();
    }

    @Test
    @DisplayName("the audit log answers the trail")
    void auditLog() {
        when(policyAuditLog.execute(id, tenant, ROLE)).thenReturn(Mono.just(List.of(new AuditEntryResponse(now, "INITIATED", EXECUTOR, null, "ACTIVE", null))));

        StepVerifier.create(controller().auditLog(id, tenantHeader, ROLE)).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
    }

    @Test
    @DisplayName("a new run is 201 and one already reported is 200")
    void reportRunStatus() {
        ReportBackupRunRequest request = new ReportBackupRunRequest(UUID.randomUUID(), RunKind.BACKUP, Outcome.SUCCEEDED, now, now, null, null);
        when(reportRun.execute(tenant, request, EXECUTOR, ROLE)).thenReturn(Mono.just(new Reported(runResponse(), true))).thenReturn(Mono.just(new Reported(runResponse(), false)));

        StepVerifier.create(controller().reportRun(tenantHeader, EXECUTOR, ROLE, request)).assertNext(response -> assertEquals(HttpStatus.CREATED, response.getStatus())).verifyComplete();
        StepVerifier.create(controller().reportRun(tenantHeader, EXECUTOR, ROLE, request)).assertNext(response -> assertEquals(HttpStatus.OK, response.getStatus())).verifyComplete();
    }

    @Test
    @DisplayName("one run and the run list, with the filter and the limit")
    void runs() {
        UUID policyId = UUID.randomUUID();
        when(retrieveRun.execute(id, tenant, ROLE)).thenReturn(Mono.just(runResponse()));
        when(retrieveRuns.execute(tenant, new Filter(policyId, RunKind.BACKUP, Outcome.FAILED), 10, ROLE)).thenReturn(Flux.just(runResponse()));

        StepVerifier.create(controller().retrieveRun(id, tenantHeader, ROLE)).assertNext(response -> assertEquals(HttpStatus.OK, response.getStatus())).verifyComplete();
        StepVerifier.create(controller().retrieveRuns(tenantHeader, ROLE, policyId, RunKind.BACKUP, Outcome.FAILED, 10)).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
    }
}
