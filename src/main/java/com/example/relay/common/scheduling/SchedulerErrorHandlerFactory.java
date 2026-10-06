package com.example.relay.common.scheduling;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ErrorHandler;

public class SchedulerErrorHandlerFactory {

    private static final Logger log = LoggerFactory.getLogger(SchedulerErrorHandlerFactory.class);

    private final MeterRegistry registry;

    public SchedulerErrorHandlerFactory(MeterRegistry registry) {
        this.registry = registry;
    }

    public ErrorHandler forScheduler(String schedulerName) {
        return error -> {
            registry.counter("relay.scheduler.errors", "scheduler", schedulerName).increment();
            log.error("Recurring task failed on scheduler {}", schedulerName, error);
        };
    }
}
