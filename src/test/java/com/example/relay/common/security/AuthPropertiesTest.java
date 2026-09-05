package com.example.relay.common.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

public class AuthPropertiesTest {

    @Test
    void validate_throws_whenAccessTokenTtlIsNotShorterThanIdleWindow() {
        AuthProperties underTest = new AuthProperties();
        underTest.setAccessTokenTtl(Duration.ofDays(30));
        underTest.setRefreshIdleWindow(Duration.ofDays(30));

        assertThrows(IllegalStateException.class, underTest::validate);
    }

    @Test
    void validate_passes_withTheShippedDefaults() {
        assertDoesNotThrow(new AuthProperties()::validate);
    }
}
