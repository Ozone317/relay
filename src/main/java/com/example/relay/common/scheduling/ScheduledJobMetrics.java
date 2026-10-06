package com.example.relay.common.scheduling;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Records scheduler admission lag and callback execution time using fixed job names. */
public class ScheduledJobMetrics {

    private static final long FIRST_INVOCATION = Long.MIN_VALUE;

    private final MeterRegistry registry;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<ScheduledJob, AtomicLong> expectedStarts = new ConcurrentHashMap<>();

    public ScheduledJobMetrics(MeterRegistry registry) {
        this(registry, System::nanoTime);
    }

    ScheduledJobMetrics(MeterRegistry registry, LongSupplier nanoTime) {
        this.registry = registry;
        this.nanoTime = nanoTime;
    }

    public void run(ScheduledJob job, Duration fixedDelay, Runnable callback) {
        AtomicLong expectedStart = expectedStarts.computeIfAbsent(job, ignored -> new AtomicLong(FIRST_INVOCATION));
        long startedAt = nanoTime.getAsLong();
        long previousExpectedStart = expectedStart.get();
        if (previousExpectedStart != FIRST_INVOCATION) {
            long lag = Math.max(0, startedAt - previousExpectedStart);
            Timer.builder("relay.scheduler.invocation.lag").tag("job", job.metricTag()).register(registry)
                    .record(lag, TimeUnit.NANOSECONDS);
        }

        boolean succeeded = false;
        try {
            callback.run();
            succeeded = true;
        } finally {
            long completedAt = nanoTime.getAsLong();
            expectedStart.set(completedAt + fixedDelay.toNanos());
            Timer.builder("relay.scheduler.callback.duration").tag("job", job.metricTag())
                    .tag("outcome", succeeded ? "success" : "failure").register(registry)
                    .record(completedAt - startedAt, TimeUnit.NANOSECONDS);
        }
    }

    public void recordAdmissionDenied(ScheduledJob job) {
        Counter.builder("relay.scheduler.admission.denied")
                .tag("job", job.metricTag())
                .register(registry)
                .increment();
    }
}
