package com.example.relay.support;

import com.example.relay.common.scheduling.ScheduledCallbackAdmission;
import com.example.relay.common.scheduling.ScheduledCallbackRunner;
import com.example.relay.common.scheduling.ScheduledJobMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public final class ScheduledCallbackTestSupport {

    private ScheduledCallbackTestSupport() {}

    public static ScheduledCallbackRunner openRunner() {
        return runner(new ScheduledJobMetrics(new SimpleMeterRegistry()));
    }

    public static ScheduledCallbackRunner runner(ScheduledJobMetrics metrics) {
        return new ScheduledCallbackRunner(
                new ScheduledCallbackAdmission(new AnnotationConfigApplicationContext()), metrics);
    }
}
