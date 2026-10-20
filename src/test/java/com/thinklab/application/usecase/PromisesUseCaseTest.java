package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.PromiseResponse;
import com.thinklab.domain.exception.BackupAccessDeniedException;
import com.thinklab.domain.model.BackupPolicy;
import com.thinklab.domain.model.BackupPolicy.PolicyStatus;
import com.thinklab.domain.repository.BackupPolicyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromisesUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private final UUID org = UUID.randomUUID();
    private final UUID asset = UUID.randomUUID();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private BackupPolicy policy(String name, PolicyStatus status, Instant created, Instant lastSuccess) {
        return BackupPolicy.reconstitute(UUID.randomUUID(), org, name, asset, 24, 48, 120, 30, null, status, created, created, null, lastSuccess, null, null, List.of());
    }

    private RetrievePromisesUseCase useCase(BackupPolicy... found) {
        BackupPolicyRepository repository = mock(BackupPolicyRepository.class);
        when(repository.findAll(org)).thenReturn(Flux.just(found));
        return new RetrievePromisesUseCase(repository, clock);
    }

    @Test
    @DisplayName("every broken promise, and every other that changed within the days asked, broken first; each with the fixed code, the status and when it changed")
    void selects() {
        BackupPolicy broken = policy("Broken", PolicyStatus.ACTIVE, NOW.minus(Duration.ofDays(90)), NOW.minus(Duration.ofDays(60)));
        BackupPolicy recovered = policy("Recovered", PolicyStatus.ACTIVE, NOW.minus(Duration.ofDays(90)), NOW.minus(Duration.ofHours(2)));
        BackupPolicy paused = policy("Paused", PolicyStatus.PAUSED, NOW.minus(Duration.ofDays(2)), null);

        StepVerifier.create(useCase(recovered, broken, paused).execute(org, null, null, "ENGINEER")).assertNext(promises -> {
            assertEquals(List.of("Broken", "Recovered", "Paused"), promises.stream().map(PromiseResponse::name).toList());
            assertEquals("BROKEN", promises.get(0).verdict());
            assertEquals("BACKUP_RPO_BREACHED", promises.get(0).code());
            assertEquals("ACTIVE", promises.get(0).status());
            assertEquals(asset, promises.get(0).assetId());
            assertEquals("KEPT", promises.get(1).verdict());
            assertEquals(NOW.minus(Duration.ofHours(2)), promises.get(1).changedAt());
            assertEquals("NOT_JUDGED", promises.get(2).verdict());
            assertEquals("PAUSED", promises.get(2).status());
        }).verifyComplete();
    }

    @Test
    @DisplayName("the window is the days asked: a promise that changed earlier is left out, and the limit applies to each kind")
    void window() {
        BackupPolicy old = policy("Old", PolicyStatus.ACTIVE, NOW.minus(Duration.ofDays(90)), NOW.minus(Duration.ofHours(5)));
        BackupPolicy olderStill = policy("Older", PolicyStatus.ACTIVE, NOW.minus(Duration.ofDays(90)), NOW.minus(Duration.ofHours(30)));
        BackupPolicy brokenOne = policy("B1", PolicyStatus.ACTIVE, NOW.minus(Duration.ofDays(90)), NOW.minus(Duration.ofDays(60)));
        BackupPolicy brokenTwo = policy("B2", PolicyStatus.ACTIVE, NOW.minus(Duration.ofDays(90)), NOW.minus(Duration.ofDays(61)));

        StepVerifier.create(useCase(olderStill, old, brokenOne, brokenTwo).execute(org, 1, 1, "ENGINEER"))
                .assertNext(promises -> assertEquals(List.of("B1", "Old"), promises.stream().map(PromiseResponse::name).toList())).verifyComplete();
    }

    @Test
    @DisplayName("the days must be 1 to 365 and the limit 1 to 1000; a requester is refused")
    void validation() {
        RetrievePromisesUseCase useCase = useCase();

        assertThrows(IllegalArgumentException.class, () -> useCase.execute(org, 0, null, "ENGINEER").block());
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(org, 366, null, "ENGINEER").block());
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(org, null, 0, "ENGINEER").block());
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(org, null, 1001, "ENGINEER").block());
        StepVerifier.create(useCase.execute(org, 365, 1000, "ENGINEER")).expectNext(List.of()).verifyComplete();
        assertThrows(BackupAccessDeniedException.class, () -> useCase.execute(org, null, null, "REQUESTER").block());
    }

    @Test
    @DisplayName("the selection is a utility class")
    void utility() throws ReflectiveOperationException {
        Constructor<PromiseSelection> constructor = PromiseSelection.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }
}
