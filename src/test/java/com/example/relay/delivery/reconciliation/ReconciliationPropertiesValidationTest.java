package com.example.relay.delivery.reconciliation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReconciliationPropertiesValidationTest {

    @Test
    void validate_throws_whenCreatedGraceIsShorterThanInterval() {
        ReconciliationProperties properties = new ReconciliationProperties();
        properties.setInterval(Duration.ofSeconds(30));
        properties.setCreatedGrace(Duration.ofSeconds(10));

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void validate_passes_whenCreatedGraceEqualsInterval() {
        ReconciliationProperties properties = new ReconciliationProperties();
        properties.setInterval(Duration.ofSeconds(30));
        properties.setCreatedGrace(Duration.ofSeconds(30));

        assertDoesNotThrow(properties::validate);
    }

    @Test
    void validate_passes_whenCreatedGraceExceedsInterval() {
        ReconciliationProperties properties = new ReconciliationProperties();
        properties.setInterval(Duration.ofSeconds(30));
        properties.setCreatedGrace(Duration.ofSeconds(60));

        assertDoesNotThrow(properties::validate);
    }

    @Test
    void validate_throws_whenDeadLetterGraceIsShorterThanInterval() {
        ReconciliationProperties properties = new ReconciliationProperties();
        properties.setInterval(Duration.ofSeconds(30));
        properties.setDeadLetterGrace(Duration.ofSeconds(10));

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void validate_passes_whenDeadLetterGraceEqualsInterval() {
        ReconciliationProperties properties = new ReconciliationProperties();
        properties.setInterval(Duration.ofSeconds(30));
        properties.setDeadLetterGrace(Duration.ofSeconds(30));

        assertDoesNotThrow(properties::validate);
    }
}
