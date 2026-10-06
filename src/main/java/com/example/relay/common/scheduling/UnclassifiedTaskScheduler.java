package com.example.relay.common.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

public class UnclassifiedTaskScheduler implements TaskScheduler {

    private static final String CLASSIFICATION_MARKER =
            "Every production @Scheduled method must declare an approved scheduler";

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
        throw unclassified();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
        throw unclassified();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
        throw unclassified();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
        throw unclassified();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
        throw unclassified();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
        throw unclassified();
    }

    private IllegalStateException unclassified() {
        return new IllegalStateException(CLASSIFICATION_MARKER);
    }
}
