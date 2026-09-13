package com.example.relay.user.recovery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PasswordResetEmailRecoveryPropertiesValidationTest {

    @Test
    void validate_throws_whenMaxRecoveryWindowIsShorterThanGrace() {
        PasswordResetEmailRecoveryProperties properties = new PasswordResetEmailRecoveryProperties();
        properties.setGrace(Duration.ofSeconds(90));
        properties.setMaxRecoveryWindow(Duration.ofSeconds(30));

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void validate_throws_whenMaxRecoveryWindowEqualsGrace() {
        // A row's firstRequestedAt already lags updatedAt by more than `grace` the very first time it becomes a
        // sweep candidate at all (see PasswordResetEmailRecoverySweeper's own candidate-query threshold) - so
        // maxRecoveryWindow == grace would give up on a chain before it ever gets one real recovery attempt.
        PasswordResetEmailRecoveryProperties properties = new PasswordResetEmailRecoveryProperties();
        properties.setGrace(Duration.ofSeconds(90));
        properties.setMaxRecoveryWindow(Duration.ofSeconds(90));

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void validate_passes_whenMaxRecoveryWindowExceedsGrace() {
        PasswordResetEmailRecoveryProperties properties = new PasswordResetEmailRecoveryProperties();
        properties.setGrace(Duration.ofSeconds(90));
        properties.setMaxRecoveryWindow(Duration.ofHours(1));

        assertDoesNotThrow(properties::validate);
    }

    @Test
    void validate_throws_whenMaxRecoveryWindowExceedsGrace_butNotGracePlusInterval() {
        // A candidate only becomes visible to the sweep once now - updatedAt > grace, but the next tick that
        // actually observes it can land up to a further `interval` later - so grace alone understates the true
        // worst-case delay before a row's first genuine recovery attempt.
        PasswordResetEmailRecoveryProperties properties = new PasswordResetEmailRecoveryProperties();
        properties.setInterval(Duration.ofSeconds(90));
        properties.setGrace(Duration.ofSeconds(90));
        properties.setMaxRecoveryWindow(Duration.ofSeconds(150));

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void validate_passes_whenMaxRecoveryWindowExceedsGracePlusInterval() {
        PasswordResetEmailRecoveryProperties properties = new PasswordResetEmailRecoveryProperties();
        properties.setInterval(Duration.ofSeconds(90));
        properties.setGrace(Duration.ofSeconds(90));
        properties.setMaxRecoveryWindow(Duration.ofSeconds(181));

        assertDoesNotThrow(properties::validate);
    }
}
