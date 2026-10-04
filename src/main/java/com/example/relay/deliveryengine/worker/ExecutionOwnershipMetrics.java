package com.example.relay.deliveryengine.worker;

import com.example.relay.attempt.domain.AttemptStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ExecutionOwnershipMetrics {

    public static final String OWNERSHIP_LOST_COUNTER = "relay.delivery.execution.ownership.lost";

    private final MeterRegistry meterRegistry;

    public ExecutionOwnershipMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordOwnershipLost(String completion, AttemptStatus currentStatus) {
        meterRegistry.counter(OWNERSHIP_LOST_COUNTER,
                "completion", completion,
                "current_status", currentStatus == null ? "missing" : currentStatus.name()).increment();
    }
}
