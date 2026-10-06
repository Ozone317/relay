package com.example.relay.deliveryengine.scheduling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;

import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.attempt.infrastructure.AttemptExecutionCandidate;
import com.example.relay.attempt.infrastructure.AttemptExecutionRepository;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.infrastructure.ReadyWorkRepository;
import com.example.relay.deliveryengine.dispatcher.ReadyWorkDispatcher;
import com.example.relay.deliveryengine.publisher.AttemptPublisher;
import com.example.relay.deliveryengine.publisher.ReadyTaskPublisher;
import com.example.relay.deliveryengine.reconciliation.ReconciliationProperties;
import com.example.relay.deliveryengine.reconciliation.ReconciliationSweeper;
import com.example.relay.deliveryengine.retry.RetryProperties;
import com.example.relay.deliveryengine.retry.RetryScheduler;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.deliveryengine.worker.ExecutionOwnershipMetrics;
import com.example.relay.support.ScheduledCallbackTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;

class ScheduledLoopGatingTest {

    @Test
    void disabledReadyDispatcherScheduleStillAllowsManualDispatch() {
        ReadyWorkRepository repository = mock(ReadyWorkRepository.class);
        ReadyTaskPublisher publisher = mock(ReadyTaskPublisher.class);
        RetryProperties properties = new RetryProperties();
        properties.setSchedulingEnabled(false);
        ReadyWorkDispatcher dispatcher = new ReadyWorkDispatcher(repository, publisher, directExecutor(), properties,
                ScheduledCallbackTestSupport.openRunner());

        dispatcher.scheduledDispatch();
        verifyNoInteractions(repository, publisher);

        when(repository.claimUnpublishedReady(any(), any(), anyInt())).thenReturn(List.of());
        dispatcher.dispatchOnce();
        verify(repository).claimUnpublishedReady(any(), eq(properties.getUnconfirmedReadyGrace()),
                eq(properties.getDispatcherBatchSize()));
    }

    @Test
    void disabledRetryScheduleStillAllowsManualPromotion() {
        ReadyWorkRepository repository = mock(ReadyWorkRepository.class);
        RetryProperties properties = new RetryProperties();
        properties.setSchedulingEnabled(false);
        RetryScheduler scheduler = new RetryScheduler(repository, properties, ScheduledCallbackTestSupport.openRunner());

        scheduler.scheduledReleaseDueRetries();
        verifyNoInteractions(repository);

        when(repository.promoteDueScheduled(anyInt())).thenReturn(List.of());
        scheduler.releaseDueRetries();
        verify(repository).promoteDueScheduled(properties.getSchedulerBatchSize());
    }

    @Test
    void disabledReconciliationScheduleStillAllowsManualSweep() {
        AttemptRepository attemptRepository = mock(AttemptRepository.class);
        AttemptExecutionRepository executionRepository = mock(AttemptExecutionRepository.class);
        AttemptPublisher attemptPublisher = mock(AttemptPublisher.class);
        AttemptService attemptService = mock(AttemptService.class);
        ReconciliationProperties properties = new ReconciliationProperties();
        ExecutionOwnershipMetrics metrics = new ExecutionOwnershipMetrics(new SimpleMeterRegistry());
        properties.setSchedulingEnabled(false);
        when(attemptRepository.findByStatusAndDeadLetterNotifiedAtIsNullAndUpdatedAtBefore(any(), any(), any()))
                .thenReturn(List.of());
        when(executionRepository.findStaleInFlight(any(), anyInt())).thenReturn(List.of());
        ReconciliationSweeper sweeper = new ReconciliationSweeper(
                attemptRepository, executionRepository, attemptPublisher, attemptService, properties, metrics,
                ScheduledCallbackTestSupport.openRunner());
        sweeper.scheduledSweep();
        verifyNoInteractions(attemptRepository, executionRepository, attemptPublisher, attemptService);
        sweeper.sweep();
        verify(executionRepository).findStaleInFlight(properties.getInFlightGrace(), properties.getBatchSize());
        verify(attemptRepository).findByStatusAndDeadLetterNotifiedAtIsNullAndUpdatedAtBefore(any(), any(), any());
    }

    @Test
    void staleInFlightRecoveryDoesNotPublishThroughReconciliationSweeper() {
        AttemptRepository attemptRepository = mock(AttemptRepository.class);
        AttemptExecutionRepository executionRepository = mock(AttemptExecutionRepository.class);
        AttemptPublisher attemptPublisher = mock(AttemptPublisher.class);
        AttemptService attemptService = mock(AttemptService.class);
        ReconciliationProperties properties = new ReconciliationProperties();
        ExecutionOwnershipMetrics metrics = new ExecutionOwnershipMetrics(new SimpleMeterRegistry());
        Attempt staleAttempt = mock(Attempt.class);
        java.util.UUID attemptId = java.util.UUID.randomUUID();
        properties.setSchedulingEnabled(false);
        when(staleAttempt.getId()).thenReturn(attemptId);
        when(executionRepository.findStaleInFlight(any(), anyInt())).thenReturn(List.of(
                new AttemptExecutionCandidate(attemptId, 3L, java.time.Instant.now().minusSeconds(1000))));
        when(attemptService.resetStuck(any(), anyLong(), any())).thenReturn(1);
        when(attemptRepository.findByStatusAndDeadLetterNotifiedAtIsNullAndUpdatedAtBefore(any(), any(), any()))
                .thenReturn(List.of());

        ReconciliationSweeper sweeper = new ReconciliationSweeper(
                attemptRepository, executionRepository, attemptPublisher, attemptService, properties, metrics,
                ScheduledCallbackTestSupport.openRunner());

        sweeper.sweep();

        verify(attemptService).resetStuck(eq(attemptId), eq(3L), any(Duration.class));
        verify(attemptPublisher, never()).publishToRoutingKey(any(), any());
    }

    @Test
    void schedulingIsEnabledByDefault() {
        assertTrue(propertiesAreEnabledByDefault());
    }

    private boolean propertiesAreEnabledByDefault() {
        return new RetryProperties().isSchedulingEnabled()
                && new ReconciliationProperties().isSchedulingEnabled();
    }

    private Executor directExecutor() {
        return Runnable::run;
    }
}
