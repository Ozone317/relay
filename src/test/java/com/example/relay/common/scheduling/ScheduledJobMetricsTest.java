package com.example.relay.common.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class ScheduledJobMetricsTest {

    @Test
    void recordsFirstSuccessThenAdmissionLagFromPriorCompletionAndFixedDelay() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Queue<Long> times = new ArrayDeque<>(List.of(100L, 125L, 17_000_125L, 17_000_150L));
        ScheduledJobMetrics metrics = new ScheduledJobMetrics(registry, times::remove);
        Duration delay = Duration.ofMillis(10);

        metrics.run("retry-promotion", delay, () -> {});

        assertEquals(1.0, registry.get("relay.scheduler.callback.duration")
                .tag("job", "retry-promotion").tag("outcome", "success").timer().count());
        assertEquals(25.0, registry.get("relay.scheduler.callback.duration")
                .tag("job", "retry-promotion").tag("outcome", "success").timer().totalTime(java.util.concurrent.TimeUnit.NANOSECONDS));
        assertEquals(0, registry.getMeters().stream().filter(meter -> meter.getId().getName()
                .equals("relay.scheduler.invocation.lag")).count());

        metrics.run("retry-promotion", delay, () -> {});

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

        metrics.run("reconciliation", Duration.ofNanos(20), () -> {});
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> metrics.run("reconciliation", Duration.ofNanos(20), () -> { throw failure; }));

        assertSame(failure, thrown);
        assertEquals(1.0, registry.get("relay.scheduler.callback.duration")
                .tag("job", "reconciliation").tag("outcome", "failure").timer().count());
        metrics.run("reconciliation", Duration.ofNanos(20), () -> {});
        assertEquals(2.0, registry.get("relay.scheduler.invocation.lag")
                .tag("job", "reconciliation").timer().count());
        assertEquals(5.0, registry.get("relay.scheduler.invocation.lag")
                .tag("job", "reconciliation").timer().totalTime(java.util.concurrent.TimeUnit.NANOSECONDS));
    }

    @Test
    void recordsAdmissionEvenWhenWrappedBusinessEnableCheckNoOps() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong now = new AtomicLong(0);
        ScheduledJobMetrics metrics = new ScheduledJobMetrics(registry, now::getAndIncrement);
        Duration delay = Duration.ofNanos(5);
        boolean enabled = false;
        AtomicLong businessWork = new AtomicLong();

        metrics.run("retry-dispatch", delay, () -> {
            if (enabled) {
                businessWork.incrementAndGet();
            }
        });
        now.set(8);
        metrics.run("retry-dispatch", delay, () -> {});

        assertEquals(2.0, registry.get("relay.scheduler.callback.duration")
                .tag("job", "retry-dispatch").tag("outcome", "success").timer().count());
        assertEquals(1.0, registry.get("relay.scheduler.invocation.lag")
                .tag("job", "retry-dispatch").timer().count());
        assertEquals(List.of("job"), registry.get("relay.scheduler.invocation.lag")
                .tag("job", "retry-dispatch").timer().getId().getTags().stream()
                .map(tag -> tag.getKey()).toList());
        assertEquals(0, businessWork.get());
        assertTrue(registry.getMeters().stream().noneMatch(meter -> meter.getId().getTag("enabled") != null));
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
