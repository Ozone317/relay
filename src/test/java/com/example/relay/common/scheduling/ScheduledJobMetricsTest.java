package com.example.relay.common.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.example.relay.attempt.infrastructure.ReadyWorkRepository;
import com.example.relay.deliveryengine.dispatcher.ReadyWorkDispatcher;
import com.example.relay.deliveryengine.reconciliation.ReconciliationProperties;
import com.example.relay.deliveryengine.reconciliation.ReconciliationSweeper;
import com.example.relay.deliveryengine.publisher.ReadyTaskPublisher;
import com.example.relay.deliveryengine.retry.RetryProperties;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.infrastructure.AttemptExecutionRepository;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.deliveryengine.publisher.AttemptPublisher;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import com.example.relay.deliveryengine.worker.ExecutionOwnershipMetrics;
import com.example.relay.support.ScheduledCallbackTestSupport;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ScheduledJobMetricsTest {

    @Test
    void admissionDenialDoesNotAdvanceExpectedStartBetweenAdmittedInvocations() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong clock = new AtomicLong(100);
        ScheduledJobMetrics metrics = new ScheduledJobMetrics(registry, () -> clock.getAndAdd(5));
        AnnotationConfigApplicationContext owner = new AnnotationConfigApplicationContext();
        ScheduledCallbackAdmission admission = new ScheduledCallbackAdmission(owner);
        ScheduledCallbackRunner runner = new ScheduledCallbackRunner(admission, metrics);
        Duration delay = Duration.ofNanos(10);

        metrics.run(ScheduledJob.READY_DISPATCH, delay, () -> {});
        clock.set(1_000);
        admission.onApplicationEvent(new ContextClosedEvent(owner));
        runner.run(ScheduledJob.READY_DISPATCH, delay, () -> fail("denied work must not run"));
        clock.set(200);
        metrics.run(ScheduledJob.READY_DISPATCH, delay, () -> {});

        assertEquals(1.0, registry.get("relay.scheduler.invocation.lag")
                .tag("job", "ready-dispatch").timer().count());
        assertEquals(85.0, registry.get("relay.scheduler.invocation.lag")
                .tag("job", "ready-dispatch").timer()
                .totalTime(java.util.concurrent.TimeUnit.NANOSECONDS));
        assertEquals(1.0, registry.get("relay.scheduler.admission.denied")
                .tag("job", "ready-dispatch").counter().count());
        assertEquals(2.0, registry.get("relay.scheduler.callback.duration")
                .tag("job", "ready-dispatch").tag("outcome", "success").timer().count());
    }

    @Test
    void admissionDenialBeforeFirstInvocationDoesNotCreateInitialLag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong clock = new AtomicLong(900);
        ScheduledJobMetrics metrics = new ScheduledJobMetrics(registry, () -> clock.getAndAdd(5));
        AnnotationConfigApplicationContext owner = new AnnotationConfigApplicationContext();
        ScheduledCallbackAdmission admission = new ScheduledCallbackAdmission(owner);
        ScheduledCallbackRunner runner = new ScheduledCallbackRunner(admission, metrics);
        Duration delay = Duration.ofNanos(10);

        admission.onApplicationEvent(new ContextClosedEvent(owner));
        runner.run(ScheduledJob.PASSWORD_RESET_EMAIL_RECOVERY, delay, () -> fail("denied work must not run"));
        clock.set(1_000);
        metrics.run(ScheduledJob.PASSWORD_RESET_EMAIL_RECOVERY, delay, () -> {});

        assertEquals(0, registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals("relay.scheduler.invocation.lag")).count());
        assertEquals(1.0, registry.get("relay.scheduler.callback.duration")
                .tag("job", "password-reset-email-recovery").tag("outcome", "success").timer().count());
    }

    @Test
    void admissionDenialHasOnlyOneBoundedCounterAndNoCallbackMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ScheduledJobMetrics metrics = new ScheduledJobMetrics(registry);

        metrics.recordAdmissionDenied(ScheduledJob.READY_DISPATCH);

        assertEquals(1.0, registry.get("relay.scheduler.admission.denied")
                .tag("job", "ready-dispatch").counter().count());
        assertTrue(registry.getMeters().stream().noneMatch(meter -> meter.getId().getName().equals(
                "relay.scheduler.callback.duration")));
        assertTrue(registry.getMeters().stream().noneMatch(meter -> meter.getId().getName().equals(
                "relay.scheduler.invocation.lag")));
        assertEquals(List.of("job"), registry.get("relay.scheduler.admission.denied")
                .tag("job", "ready-dispatch").counter().getId().getTags().stream()
                .map(tag -> tag.getKey()).toList());
    }

    @Test
    void recordsFirstSuccessThenAdmissionLagFromPriorCompletionAndFixedDelay() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Queue<Long> times = new ArrayDeque<>(List.of(100L, 125L, 17_000_125L, 17_000_150L));
        ScheduledJobMetrics metrics = new ScheduledJobMetrics(registry, times::remove);
        Duration delay = Duration.ofMillis(10);

        metrics.run(ScheduledJob.RETRY_PROMOTION, delay, () -> {});

        assertEquals(1.0, registry.get("relay.scheduler.callback.duration")
                .tag("job", "retry-promotion").tag("outcome", "success").timer().count());
        assertEquals(25.0, registry.get("relay.scheduler.callback.duration")
                .tag("job", "retry-promotion").tag("outcome", "success").timer().totalTime(java.util.concurrent.TimeUnit.NANOSECONDS));
        assertEquals(0, registry.getMeters().stream().filter(meter -> meter.getId().getName()
                .equals("relay.scheduler.invocation.lag")).count());

        metrics.run(ScheduledJob.RETRY_PROMOTION, delay, () -> {});

        assertEquals(1, registry.get("relay.scheduler.invocation.lag")
                .tag("job", "retry-promotion").timer().count());
        assertEquals(7_000_000.0, registry.get("relay.scheduler.invocation.lag")
                .tag("job", "retry-promotion").timer().totalTime(java.util.concurrent.TimeUnit.NANOSECONDS));
        assertEquals(List.of("job", "outcome"), registry.get("relay.scheduler.callback.duration")
                .tag("job", "retry-promotion").tag("outcome", "success").timer().getId().getTags().stream()
                .map(tag -> tag.getKey()).sorted().toList());
    }

    @Test
    void recordsFailureRethrowsSameExceptionAndAdvancesExpectedStart() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Queue<Long> times = new ArrayDeque<>(List.of(10L, 15L, 40L, 45L, 60L, 80L));
        LongSupplier nanoTime = times::remove;
        ScheduledJobMetrics metrics = new ScheduledJobMetrics(registry, nanoTime);
        RuntimeException failure = new RuntimeException("boom");

        metrics.run(ScheduledJob.DELIVERY_RECONCILIATION, Duration.ofNanos(20), () -> {});
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> metrics.run(ScheduledJob.DELIVERY_RECONCILIATION, Duration.ofNanos(20), () -> { throw failure; }));

        assertSame(failure, thrown);
        assertEquals(1.0, registry.get("relay.scheduler.callback.duration")
                .tag("job", "delivery-reconciliation").tag("outcome", "failure").timer().count());
        metrics.run(ScheduledJob.DELIVERY_RECONCILIATION, Duration.ofNanos(20), () -> {});
        assertEquals(2.0, registry.get("relay.scheduler.invocation.lag")
                .tag("job", "delivery-reconciliation").timer().count());
        assertEquals(5.0, registry.get("relay.scheduler.invocation.lag")
                .tag("job", "delivery-reconciliation").timer().totalTime(java.util.concurrent.TimeUnit.NANOSECONDS));
    }

    @Test
    void recordsAdmissionEvenWhenWrappedBusinessEnableCheckNoOps() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong now = new AtomicLong(0);
        ScheduledJobMetrics metrics = new ScheduledJobMetrics(registry, now::getAndIncrement);
        Duration delay = Duration.ofNanos(5);
        boolean enabled = false;
        AtomicLong businessWork = new AtomicLong();

        metrics.run(ScheduledJob.READY_DISPATCH, delay, () -> {
            if (enabled) {
                businessWork.incrementAndGet();
            }
        });
        now.set(8);
        metrics.run(ScheduledJob.READY_DISPATCH, delay, () -> {});

        assertEquals(2.0, registry.get("relay.scheduler.callback.duration")
                .tag("job", "ready-dispatch").tag("outcome", "success").timer().count());
        assertEquals(1.0, registry.get("relay.scheduler.invocation.lag")
                .tag("job", "ready-dispatch").timer().count());
        assertEquals(List.of("job"), registry.get("relay.scheduler.invocation.lag")
                .tag("job", "ready-dispatch").timer().getId().getTags().stream()
                .map(tag -> tag.getKey()).toList());
        assertEquals(0, businessWork.get());
        assertTrue(registry.getMeters().stream().noneMatch(meter -> meter.getId().getTag("enabled") != null));
    }

    @Test
    void disabledProductionDispatcherRecordsAdmissionWithoutBusinessWork() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Queue<Long> times = new ArrayDeque<>(List.of(0L, 2L, 8L, 10L));
        ScheduledJobMetrics metrics = new ScheduledJobMetrics(registry, times::remove);
        ReadyWorkRepository repository = mock(ReadyWorkRepository.class);
        ReadyTaskPublisher publisher = mock(ReadyTaskPublisher.class);
        RetryProperties properties = new RetryProperties();
        properties.setSchedulingEnabled(false);
        properties.setDispatcherInterval(Duration.ofNanos(5));
        ReadyWorkDispatcher dispatcher = new ReadyWorkDispatcher(repository, publisher, Runnable::run, properties,
                ScheduledCallbackTestSupport.runner(metrics));

        dispatcher.scheduledDispatch();
        dispatcher.scheduledDispatch();

        assertEquals(2.0, registry.get("relay.scheduler.callback.duration")
                .tag("job", "ready-dispatch").tag("outcome", "success").timer().count());
        assertEquals(4.0, registry.get("relay.scheduler.callback.duration")
                .tag("job", "ready-dispatch").tag("outcome", "success").timer()
                .totalTime(java.util.concurrent.TimeUnit.NANOSECONDS));
        assertEquals(1, registry.get("relay.scheduler.invocation.lag")
                .tag("job", "ready-dispatch").timer().count());
        assertEquals(1.0, registry.get("relay.scheduler.invocation.lag")
                .tag("job", "ready-dispatch").timer()
                .totalTime(java.util.concurrent.TimeUnit.NANOSECONDS));
        verifyNoInteractions(repository, publisher);
    }

    @Test
    void disabledProductionReconciliationWrapperRecordsApprovedJobWithoutBusinessWork() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Queue<Long> times = new ArrayDeque<>(List.of(0L, 2L, 8L, 10L));
        ScheduledJobMetrics metrics = new ScheduledJobMetrics(registry, times::remove);
        AttemptRepository attempts = mock(AttemptRepository.class);
        AttemptExecutionRepository executions = mock(AttemptExecutionRepository.class);
        AttemptPublisher publisher = mock(AttemptPublisher.class);
        AttemptService service = mock(AttemptService.class);
        ReconciliationProperties properties = new ReconciliationProperties();
        properties.setSchedulingEnabled(false);
        properties.setInterval(Duration.ofNanos(5));
        ReconciliationSweeper sweeper = new ReconciliationSweeper(attempts, executions, publisher, service,
                properties, mock(ExecutionOwnershipMetrics.class), ScheduledCallbackTestSupport.runner(metrics));

        sweeper.scheduledSweep();
        sweeper.scheduledSweep();

        assertEquals(2.0, registry.get("relay.scheduler.callback.duration")
                .tag("job", "delivery-reconciliation").tag("outcome", "success").timer().count());
        assertEquals(1, registry.get("relay.scheduler.invocation.lag")
                .tag("job", "delivery-reconciliation").timer().count());
        assertEquals(1.0, registry.get("relay.scheduler.invocation.lag")
                .tag("job", "delivery-reconciliation").timer()
                .totalTime(java.util.concurrent.TimeUnit.NANOSECONDS));
        verifyNoInteractions(attempts, executions, publisher, service);
    }

    @Test
    void schedulerErrorHandlerIncrementsOnlyItsFixedSchedulerTagAndReturns() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var handler = new SchedulerErrorHandlerFactory(registry).forScheduler(SchedulerNames.DELIVERY_PROGRESS);

        handler.handleError(new IllegalStateException("scheduler callback failed"));

        assertEquals(1.0, registry.get("relay.scheduler.errors")
                .tag("scheduler", SchedulerNames.DELIVERY_PROGRESS).counter().count());
        assertEquals(List.of("scheduler"), registry.get("relay.scheduler.errors")
                .tag("scheduler", SchedulerNames.DELIVERY_PROGRESS).counter().getId().getTags().stream()
                .map(tag -> tag.getKey()).toList());
    }
}
