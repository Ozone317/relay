package com.example.relay.user.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.relay.support.SharedPostgresContainer;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Uses a NON-default max-recovery-window value deliberately - the real application.properties value happens to equal
 * the field's own code default (1h), which would let a typo'd property key bind silently (see this project's own
 * AuthPropertiesBindingTest precedent for why a vacuous default-matches-default assertion is worthless here).
 */
@SpringBootTest
@TestPropertySource(properties = {"relay.password-reset.email-recovery.max-recovery-window=2h"})
class PasswordResetEmailRecoveryPropertiesBindingTest implements SharedPostgresContainer {

    @Autowired
    private PasswordResetEmailRecoveryProperties properties;

    @Test
    void maxRecoveryWindow_bindsFromItsConfiguredKey() {
        assertEquals(Duration.ofHours(2), properties.getMaxRecoveryWindow());
    }
}
