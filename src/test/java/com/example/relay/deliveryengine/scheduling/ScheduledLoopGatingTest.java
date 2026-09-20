package com.example.relay.deliveryengine.scheduling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

class ScheduledLoopGatingTest {

    @Test
    void disabledReadyDispatcherScheduleStillAllowsManualDispatch() {
        ReadyWorkRepository repository = mock(ReadyWorkRepository.class);
        ReadyTaskPublisher publisher = mock(ReadyTaskPublisher.class);
        RetryProperties properties = new RetryProperties();
        properties.setSchedulingEnabled(false);
        ReadyWorkDispatcher dispatcher = new ReadyWorkDispatcher(repository, publisher, directExecutor(), properties);

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
        RetryScheduler scheduler = new RetryScheduler(repository, properties);

        scheduler.scheduledReleaseDueRetries();
        verifyNoInteractions(repository);

        when(repository.promoteDueScheduled(anyInt())).thenReturn(List.of());
        scheduler.releaseDueRetries();
        verify(repository).promoteDueScheduled(properties.getSchedulerBatchSize());
    }

    @Test
    void disabledReconciliationScheduleStillAllowsManualSweep() {
        AttemptRepository attemptRepository = mock(AttemptRepository.class);
        AttemptPublisher attemptPublisher = mock(AttemptPublisher.class);
        AttemptService attemptService = mock(AttemptService.class);
        ReconciliationProperties properties = new ReconciliationProperties();
        properties.setSchedulingEnabled(false);
        ReconciliationSweeper sweeper = new ReconciliationSweeper(
                attemptRepository, attemptPublisher, attemptService, properties);

        sweeper.scheduledSweep();
        verifyNoInteractions(attemptRepository, attemptPublisher, attemptService);

        when(attemptRepository.findByStatusAndUpdatedAtBefore(any(), any(), any())).thenReturn(List.of());
        when(attemptRepository.findByStatusAndDeadLetterNotifiedAtIsNullAndUpdatedAtBefore(any(), any(), any()))
                .thenReturn(List.of());
        sweeper.sweep();
        verify(attemptRepository).findByStatusAndUpdatedAtBefore(any(), any(), any());
        verify(attemptRepository).findByStatusAndDeadLetterNotifiedAtIsNullAndUpdatedAtBefore(any(), any(), any());
    }

    @Test
    void staleInFlightRecoveryDoesNotPublishThroughReconciliationSweeper() {
        AttemptRepository attemptRepository = mock(AttemptRepository.class);
        AttemptPublisher attemptPublisher = mock(AttemptPublisher.class);
        AttemptService attemptService = mock(AttemptService.class);
        ReconciliationProperties properties = new ReconciliationProperties();
        Attempt staleAttempt = mock(Attempt.class);
        java.util.UUID attemptId = java.util.UUID.randomUUID();
        properties.setSchedulingEnabled(false);
        when(staleAttempt.getId()).thenReturn(attemptId);
        when(attemptRepository.findByStatusAndUpdatedAtBefore(any(), any(), any()))
                .thenReturn(List.of(staleAttempt));
        when(attemptService.resetStuck(any(), any(), any())).thenReturn(1);
        when(attemptRepository.findByStatusAndDeadLetterNotifiedAtIsNullAndUpdatedAtBefore(any(), any(), any()))
                .thenReturn(List.of());

        ReconciliationSweeper sweeper = new ReconciliationSweeper(
                attemptRepository, attemptPublisher, attemptService, properties);

        sweeper.sweep();

        verify(attemptService).resetStuck(eq(attemptId), any(), any());
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
