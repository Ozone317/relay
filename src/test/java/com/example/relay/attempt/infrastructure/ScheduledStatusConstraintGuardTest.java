package com.example.relay.attempt.infrastructure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

/**
 * Unit tests for the guard's decision logic (mocked EntityManager - no real Postgres needed, since
 * the branching only depends on the string {@code pg_get_constraintdef} would have returned, not on
 * any Postgres-specific query execution semantics) plus a check that the bean only registers under
 * the {@code docker} profile.
 */
@ExtendWith(MockitoExtension.class)
public class ScheduledStatusConstraintGuardTest {

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query query;

    @Test
    void run_throwsIllegalStateException_whenConstraintExistsButOmitsScheduled() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(
                "CHECK (status = ANY (ARRAY['CREATED', 'IN_FLIGHT', 'SUCCEEDED', 'FAILED_RETRYING', 'DEAD']))"));

        ScheduledStatusConstraintGuard guard = newGuard();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard.run(null));
        assertTrue(ex.getMessage().contains("2026-09-03-add-scheduled-status.sql"),
                "exception should point the operator at the migration script");
    }

    @Test
    void run_doesNotThrow_whenConstraintIncludesScheduled() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(
                "CHECK (status = ANY (ARRAY['CREATED', 'IN_FLIGHT', 'SCHEDULED', 'SUCCEEDED', 'FAILED_RETRYING', 'DEAD']))"));

        ScheduledStatusConstraintGuard guard = newGuard();

        assertDoesNotThrow(() -> guard.run(null));
    }

    @Test
    void run_doesNotThrow_whenConstraintDoesNotExistAtAll() {
        // No row for that conname - nothing is restricting the status column, so SCHEDULED is
        // implicitly allowed; the guard has nothing to object to.
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());

        ScheduledStatusConstraintGuard guard = newGuard();

        assertDoesNotThrow(() -> guard.run(null));
    }

    private ScheduledStatusConstraintGuard newGuard() {
        ScheduledStatusConstraintGuard guard = new ScheduledStatusConstraintGuard();
        try {
            var field = ScheduledStatusConstraintGuard.class.getDeclaredField("entityManager");
            field.setAccessible(true);
            field.set(guard, entityManager);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return guard;
    }

    @Test
    void guardBean_isNotRegistered_whenDockerProfileIsInactive() {
        new ApplicationContextRunner()
                .withUserConfiguration(ScheduledStatusConstraintGuard.class)
                .run(context -> assertTrue(context.getBeansOfType(ScheduledStatusConstraintGuard.class).isEmpty(),
                        "the guard must not be registered outside the docker profile"));
    }

    @Test
    void guardBean_isRegistered_whenDockerProfileIsActive() {
        new ApplicationContextRunner()
                .withBean(EntityManagerFactory.class, () -> mock(EntityManagerFactory.class))
                .withUserConfiguration(ScheduledStatusConstraintGuard.class)
                .withPropertyValues("spring.profiles.active=docker")
                .run(context -> assertTrue(context.getBeansOfType(ScheduledStatusConstraintGuard.class).size() == 1,
                        "the guard must be registered under the docker profile"));
    }
}
