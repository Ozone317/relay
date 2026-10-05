package com.example.relay.attempt.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AttemptAllocationMetrics {

    public static final String COUNTER = "relay.attempt.allocation";
    private static final Set<String> CREATORS = Set.of("initial", "retry", "replay");
    private static final Set<String> OUTCOMES =
            Set.of("created", "rejected", "ownership_lost", "invariant_violation");

    private final MeterRegistry meterRegistry;

    public AttemptAllocationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Records service decisions before transaction commit; counters are not exact totals of durable Attempt rows.
     */
    public void record(String creator, String outcome) {
        if (!CREATORS.contains(creator) || !OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("Unsupported attempt allocation metric tags");
        }
        meterRegistry.counter(COUNTER, "creator", creator, "outcome", outcome).increment();
    }
}
