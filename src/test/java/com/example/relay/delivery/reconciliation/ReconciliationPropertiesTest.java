package com.example.relay.delivery.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "relay.reconciliation.interval=15s",
        "relay.reconciliation.created-grace=20s",
        "relay.reconciliation.in-flight-grace=45s",
        "relay.reconciliation.batch-size=25",
        "relay.reconciliation.scheduled-slack=2m",
        "relay.reconciliation.dead-letter-grace=3m"
})
class ReconciliationPropertiesTest {

    @Autowired
    private ReconciliationProperties properties;

    @Test
    void bindsAllPropertiesFromRelayReconciliationPrefix() {
        assertEquals(Duration.ofSeconds(15), properties.getInterval());
        assertEquals(Duration.ofSeconds(20), properties.getCreatedGrace());
        assertEquals(Duration.ofSeconds(45), properties.getInFlightGrace());
        assertEquals(25, properties.getBatchSize());
        assertEquals(Duration.ofMinutes(2), properties.getScheduledSlack());
        assertEquals(Duration.ofMinutes(3), properties.getDeadLetterGrace());
    }
}
