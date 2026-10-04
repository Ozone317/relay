package com.example.relay.deliveryengine.worker;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ExecutionOwnershipMetrics {

    public static final String OWNERSHIP_LOST_COUNTER = "relay.delivery.execution.ownership.lost";
    public static final String REVOKED_COUNTER = "relay.delivery.execution.revoked";

    private final MeterRegistry meterRegistry;

    public ExecutionOwnershipMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordOwnershipLost(String completion, String currentStatus) {
        meterRegistry.counter(OWNERSHIP_LOST_COUNTER,
                "completion", completion,
                "current_status", currentStatus).increment();
    }

    public void recordRevoked(String result) {
        meterRegistry.counter(REVOKED_COUNTER, "result", result).increment();
    }
}
