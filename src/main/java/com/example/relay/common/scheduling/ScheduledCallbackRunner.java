package com.example.relay.common.scheduling;

import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Composes context admission with callback metrics and business work. */
@Component
public final class ScheduledCallbackRunner {

    private final ScheduledCallbackAdmission admission;
    private final ScheduledJobMetrics metrics;

    public ScheduledCallbackRunner(ScheduledCallbackAdmission admission, ScheduledJobMetrics metrics) {
        this.admission = Objects.requireNonNull(admission);
        this.metrics = Objects.requireNonNull(metrics);
    }

    public void run(ScheduledJob job, Duration fixedDelay, Runnable businessBody) {
        boolean admitted = admission.runIfOpen(() -> metrics.run(job, fixedDelay, businessBody));
        if (!admitted) {
            metrics.recordAdmissionDenied(job);
        }
    }
}
