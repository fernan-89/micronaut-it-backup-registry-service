package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateBackupPolicyRequest;
import com.thinklab.application.dto.request.ReportBackupRunRequest;
import com.thinklab.application.dto.request.UpdateBackupPolicyRequest;
import com.thinklab.domain.exception.BackupAccessDeniedException;
import com.thinklab.domain.exception.BackupPolicyNotFoundException;
import com.thinklab.domain.exception.BackupRunNotFoundException;
import com.thinklab.domain.exception.InvalidBackupPolicyStatusException;
import com.thinklab.domain.model.BackupPolicy;
import com.thinklab.domain.model.BackupPolicy.PolicyStatus;
import com.thinklab.domain.model.BackupPolicy.Protection;
import com.thinklab.domain.model.BackupRun;
import com.thinklab.domain.model.BackupRun.FailureReason;
import com.thinklab.domain.model.BackupRun.Outcome;
import com.thinklab.domain.model.BackupRun.RunKind;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.BackupPolicyRepository;
import com.thinklab.domain.repository.BackupRunRepository;
import com.thinklab.domain.repository.BackupRunRepository.Filter;
import com.thinklab.domain.repository.BackupRunRepository.Stored;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupUseCasesTest {

    private static final String STAFF = "ENGINEER";
    private static final String REQUESTER = "REQUESTER";

    private final UUID org = UUID.randomUUID();
    private final UUID asset = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-07T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private HashServicePort hash;
    private BackupPolicyRepository policies;
    private BackupRunRepository runs;

    @BeforeEach
    void setUp() {
        hash = mock(HashServicePort.class);
        policies = mock(BackupPolicyRepository.class);
        runs = mock(BackupRunRepository.class);
    }

    private BackupPolicy policy() {
        return BackupPolicy.createNew(UUID.randomUUID(), org, "Nightly", asset, 24, 48, 120, 30, 7, "op");
    }

    private InitiateBackupPolicyRequest initiateRequest() {
        return new InitiateBackupPolicyRequest("Nightly", asset, 24, 48, 120, 30, 7);
    }

    private UpdateBackupPolicyRequest updateRequest() {
        return new UpdateBackupPolicyRequest("Renamed", asset, 12, 24, 60, 10, null);
    }

    private ReportBackupRunRequest report(UUID policyId) {
        return new ReportBackupRunRequest(policyId, RunKind.BACKUP, Outcome.SUCCEEDED, now.minusSeconds(1800), now.minusSeconds(60), 10L, null);
    }

    @Test
    @DisplayName("the access rule refuses a requester and lets staff and a missing role through; it is a utility class")
    void access() throws ReflectiveOperationException {
        BackupAccess.requireStaff(STAFF, "x");
        BackupAccess.requireStaff(null, "x");
        BackupAccessDeniedException denied = assertThrows(BackupAccessDeniedException.class, () -> BackupAccess.requireStaff(REQUESTER, "do it"));
        assertTrue(denied.getMessage().contains("do it"));
        Constructor<BackupAccess> constructor = BackupAccess.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }

    @Test
    @DisplayName("initiate spends a sovereign id, stores the policy and answers it judged now; a requester spends nothing")
    void initiate() {
        UUID id = UUID.randomUUID();
        when(hash.generateSovereignId("backup-policy-creation")).thenReturn(Mono.just(id));
        when(policies.create(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        InitiateBackupPolicyUseCase useCase = new InitiateBackupPolicyUseCase(hash, policies, clock);

        StepVerifier.create(useCase.execute(org, initiateRequest(), "op", STAFF)).assertNext(response -> {
            assertEquals(id, response.id());
            assertEquals("ACTIVE", response.status());
            assertEquals("NEVER", response.protection());
        }).verifyComplete();

        StepVerifier.create(useCase.execute(org, initiateRequest(), "op", REQUESTER)).expectError(BackupAccessDeniedException.class).verify();
        verify(hash).generateSovereignId("backup-policy-creation");
    }

    @Test
    @DisplayName("retrieve answers the tenant's policy, a 404 when it is not there, and refuses a requester")
    void retrieve() {
        BackupPolicy policy = policy();
        when(policies.findById(policy.getId(), org)).thenReturn(Mono.just(policy));
        when(policies.findById(any(), eq(UUID.fromString("00000000-0000-0000-0000-000000000001")))).thenReturn(Mono.empty());
        RetrieveBackupPolicyUseCase useCase = new RetrieveBackupPolicyUseCase(policies, clock);

        StepVerifier.create(useCase.execute(policy.getId(), org, STAFF)).assertNext(response -> assertEquals("Nightly", response.name())).verifyComplete();
        StepVerifier.create(useCase.execute(policy.getId(), UUID.fromString("00000000-0000-0000-0000-000000000001"), STAFF)).expectError(BackupPolicyNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(policy.getId(), org, REQUESTER)).expectError(BackupAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("the list filters by status, asset and protection, judged now, and refuses a requester")
    void retrieveAll() {
        BackupPolicy active = policy();
        BackupPolicy paused = BackupPolicy.createNew(UUID.randomUUID(), org, "Other", UUID.randomUUID(), 24, 48, 120, 30, null, "op");
        paused.pause("op");
        when(policies.findAll(org)).thenReturn(Flux.just(active, paused));
        RetrieveBackupPoliciesUseCase useCase = new RetrieveBackupPoliciesUseCase(policies, clock);

        StepVerifier.create(useCase.execute(org, null, null, null, STAFF)).expectNextCount(2).verifyComplete();
        StepVerifier.create(useCase.execute(org, PolicyStatus.PAUSED, null, null, STAFF)).expectNextCount(1).verifyComplete();
        StepVerifier.create(useCase.execute(org, null, asset, null, STAFF)).assertNext(response -> assertEquals(active.getId(), response.id())).verifyComplete();
        StepVerifier.create(useCase.execute(org, null, null, Protection.PAUSED, STAFF)).assertNext(response -> assertEquals(paused.getId(), response.id())).verifyComplete();
        StepVerifier.create(useCase.execute(org, null, null, Protection.RPO_BREACHED, STAFF)).verifyComplete();
        StepVerifier.create(useCase.execute(org, null, null, null, REQUESTER)).expectError(BackupAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("update and control load the policy, apply the change and save it with the status loaded; an unknown policy is a 404 and a requester is refused")
    void updateAndControl() {
        BackupPolicy policy = policy();
        when(policies.findById(policy.getId(), org)).thenReturn(Mono.just(policy));
        when(policies.findById(any(), eq(UUID.fromString("00000000-0000-0000-0000-000000000001")))).thenReturn(Mono.empty());
        when(policies.save(any(), any(), any())).thenReturn(Mono.empty());
        BackupPolicyWorkflow workflow = new BackupPolicyWorkflow(policies);
        UpdateBackupPolicyUseCase update = new UpdateBackupPolicyUseCase(workflow);
        ControlBackupPolicyUseCase control = new ControlBackupPolicyUseCase(workflow);

        StepVerifier.create(update.execute(policy.getId(), org, updateRequest(), "op", STAFF)).verifyComplete();
        assertEquals("Renamed", policy.getName());
        StepVerifier.create(control.execute(policy.getId(), org, ControlBackupPolicyUseCase.Action.PAUSE, "op", STAFF)).verifyComplete();
        assertEquals(PolicyStatus.PAUSED, policy.getStatus());
        StepVerifier.create(control.execute(policy.getId(), org, ControlBackupPolicyUseCase.Action.PAUSE, "op", STAFF)).expectError(InvalidBackupPolicyStatusException.class).verify();
        StepVerifier.create(control.execute(policy.getId(), org, ControlBackupPolicyUseCase.Action.RESUME, "op", STAFF)).verifyComplete();
        assertEquals(PolicyStatus.ACTIVE, policy.getStatus());

        ArgumentCaptor<PolicyStatus> expected = ArgumentCaptor.forClass(PolicyStatus.class);
        verify(policies, times(3)).save(any(), expected.capture(), any());
        assertEquals(PolicyStatus.ACTIVE, expected.getAllValues().get(0));
        assertEquals(PolicyStatus.ACTIVE, expected.getAllValues().get(1));
        assertEquals(PolicyStatus.PAUSED, expected.getAllValues().get(2));

        UUID elsewhere = UUID.fromString("00000000-0000-0000-0000-000000000001");
        StepVerifier.create(update.execute(policy.getId(), elsewhere, updateRequest(), "op", STAFF)).expectError(BackupPolicyNotFoundException.class).verify();
        StepVerifier.create(update.execute(policy.getId(), org, updateRequest(), "op", REQUESTER)).expectError(BackupAccessDeniedException.class).verify();
        StepVerifier.create(control.execute(policy.getId(), org, ControlBackupPolicyUseCase.Action.PAUSE, "op", REQUESTER)).expectError(BackupAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("the audit log is the policy's trail, a 404 for an unknown policy, and staff only")
    void auditLog() {
        BackupPolicy policy = policy();
        policy.pause("op");
        when(policies.findById(policy.getId(), org)).thenReturn(Mono.just(policy));
        when(policies.findById(any(), eq(UUID.fromString("00000000-0000-0000-0000-000000000001")))).thenReturn(Mono.empty());
        RetrieveBackupPolicyAuditLogUseCase useCase = new RetrieveBackupPolicyAuditLogUseCase(policies);

        StepVerifier.create(useCase.execute(policy.getId(), org, STAFF)).assertNext(entries -> {
            assertEquals(2, entries.size());
            assertEquals("INITIATED", entries.get(0).action());
        }).verifyComplete();
        StepVerifier.create(useCase.execute(policy.getId(), UUID.fromString("00000000-0000-0000-0000-000000000001"), STAFF)).expectError(BackupPolicyNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(policy.getId(), org, REQUESTER)).expectError(BackupAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("a new run is stored and folded into the policy; the answer says it was created")
    void reportNew() {
        BackupPolicy policy = policy();
        UUID runId = UUID.randomUUID();
        when(policies.findById(policy.getId(), org)).thenReturn(Mono.just(policy));
        when(runs.findByNaturalKey(eq(org), eq(policy.getId()), eq(RunKind.BACKUP), any())).thenReturn(Mono.empty());
        when(hash.generateSovereignId("backup-run-creation")).thenReturn(Mono.just(runId));
        when(runs.create(any())).thenAnswer(invocation -> Mono.just(new Stored(invocation.getArgument(0), true)));
        when(policies.recordRun(eq(policy.getId()), eq(org), any())).thenReturn(Mono.empty());
        ReportBackupRunUseCase useCase = new ReportBackupRunUseCase(hash, policies, runs, clock);

        StepVerifier.create(useCase.execute(org, report(policy.getId()), "tool", STAFF)).assertNext(reported -> {
            assertTrue(reported.created());
            assertEquals(runId, reported.run().id());
            assertEquals("tool", reported.run().reportedBy());
        }).verifyComplete();
        verify(policies).recordRun(eq(policy.getId()), eq(org), any(BackupRun.class));
    }

    @Test
    @DisplayName("the same run reported again is the stored one, no id is spent, and the policy summary is folded again")
    void reportAgain() {
        BackupPolicy policy = policy();
        BackupRun stored = BackupRun.createNew(UUID.randomUUID(), org, policy.getId(), RunKind.BACKUP, Outcome.FAILED, now.minusSeconds(1800), now.minusSeconds(60), null,
                FailureReason.STORAGE_FULL, "tool", now);
        when(policies.findById(policy.getId(), org)).thenReturn(Mono.just(policy));
        when(runs.findByNaturalKey(eq(org), eq(policy.getId()), eq(RunKind.BACKUP), any())).thenReturn(Mono.just(stored));
        when(policies.recordRun(any(), any(), any())).thenReturn(Mono.empty());
        ReportBackupRunUseCase useCase = new ReportBackupRunUseCase(hash, policies, runs, clock);

        StepVerifier.create(useCase.execute(org, report(policy.getId()), "tool", STAFF)).assertNext(reported -> {
            assertEquals(false, reported.created());
            assertEquals(stored.getId(), reported.run().id());
            assertEquals("STORAGE_FULL", reported.run().failureReason());
        }).verifyComplete();
        verify(hash, never()).generateSovereignId(any());
        verify(runs, never()).create(any());
        verify(policies).recordRun(eq(policy.getId()), eq(org), eq(stored));
    }

    @Test
    @DisplayName("a run for a policy that is not the tenant's is a 404, a requester is refused, and a run that makes no sense is a 400")
    void reportRefused() {
        UUID unknown = UUID.randomUUID();
        when(policies.findById(unknown, org)).thenReturn(Mono.empty());
        BackupPolicy policy = policy();
        when(policies.findById(policy.getId(), org)).thenReturn(Mono.just(policy));
        when(runs.findByNaturalKey(any(), any(), any(), any())).thenReturn(Mono.empty());
        when(hash.generateSovereignId(any())).thenReturn(Mono.just(UUID.randomUUID()));
        ReportBackupRunUseCase useCase = new ReportBackupRunUseCase(hash, policies, runs, clock);

        StepVerifier.create(useCase.execute(org, report(unknown), "tool", STAFF)).expectError(BackupPolicyNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(org, report(unknown), "tool", REQUESTER)).expectError(BackupAccessDeniedException.class).verify();
        ReportBackupRunRequest failedWithoutReason = new ReportBackupRunRequest(policy.getId(), RunKind.BACKUP, Outcome.FAILED, now.minusSeconds(60), now.minusSeconds(30), null, null);
        StepVerifier.create(useCase.execute(org, failedWithoutReason, "tool", STAFF)).expectError(IllegalArgumentException.class).verify();
        verify(runs, never()).create(any());
    }

    @Test
    @DisplayName("one run: answered for the tenant, a 404 otherwise, staff only")
    void retrieveRun() {
        BackupRun run = BackupRun.createNew(UUID.randomUUID(), org, UUID.randomUUID(), RunKind.RESTORE_TEST, Outcome.SUCCEEDED, now.minusSeconds(600), now.minusSeconds(60), 5L, null,
                "tool", now);
        when(runs.findById(run.getId(), org)).thenReturn(Mono.just(run));
        when(runs.findById(any(), eq(UUID.fromString("00000000-0000-0000-0000-000000000001")))).thenReturn(Mono.empty());
        RetrieveBackupRunUseCase useCase = new RetrieveBackupRunUseCase(runs);

        StepVerifier.create(useCase.execute(run.getId(), org, STAFF)).assertNext(response -> assertEquals("RESTORE_TEST", response.kind())).verifyComplete();
        StepVerifier.create(useCase.execute(run.getId(), UUID.fromString("00000000-0000-0000-0000-000000000001"), STAFF)).expectError(BackupRunNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(run.getId(), org, REQUESTER)).expectError(BackupAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("the run list uses 50 unless asked, accepts 1 to 200, refuses anything else and a requester")
    void retrieveRuns() {
        BackupRun run = BackupRun.createNew(UUID.randomUUID(), org, UUID.randomUUID(), RunKind.BACKUP, Outcome.SUCCEEDED, now.minusSeconds(600), now.minusSeconds(60), null, null,
                "tool", now);
        Filter filter = new Filter(null, null, null);
        when(runs.findAll(eq(org), eq(filter), any(Integer.class))).thenReturn(Flux.just(run));
        RetrieveBackupRunsUseCase useCase = new RetrieveBackupRunsUseCase(runs);

        StepVerifier.create(useCase.execute(org, filter, null, STAFF)).expectNextCount(1).verifyComplete();
        verify(runs).findAll(org, filter, 50);
        StepVerifier.create(useCase.execute(org, filter, 1, STAFF)).expectNextCount(1).verifyComplete();
        StepVerifier.create(useCase.execute(org, filter, 200, STAFF)).expectNextCount(1).verifyComplete();
        verify(runs).findAll(org, filter, 200);
        StepVerifier.create(useCase.execute(org, filter, 0, STAFF)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(useCase.execute(org, filter, 201, STAFF)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(useCase.execute(org, filter, null, REQUESTER)).expectError(BackupAccessDeniedException.class).verify();
    }
}
