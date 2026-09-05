package com.example.relay.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Proves the {@code relay.auth} prefix and every field name under it actually bind.
 *
 * <p>
 * Deliberately overrides all four keys with values that differ from the field defaults. Asserting the shipped values
 * instead would be vacuous: application.properties sets exactly the defaults, so such a test passes just as happily
 * when nothing binds at all. That is the failure mode this class exists to rule out, so it must not reproduce it.
 */
@SpringBootTest
@TestPropertySource(properties = {"relay.auth.access-token-ttl=7m", "relay.auth.refresh-idle-window=3d",
        "relay.auth.cookie-secure=false", "relay.auth.allowed-origin=https://relay.example.test"})
class AuthPropertiesBindingTest {

    @Autowired
    private AuthProperties authProperties;

    @Test
    void bindsEveryConfiguredKeyOntoTheMatchingField() {
        assertEquals(Duration.ofMinutes(7), authProperties.getAccessTokenTtl());
        assertEquals(Duration.ofDays(3), authProperties.getRefreshIdleWindow());
        assertFalse(authProperties.isCookieSecure());
        assertEquals("https://relay.example.test", authProperties.getAllowedOrigin());
    }
}
