package com.example.relay.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

public class RefreshCookieFactoryTest {

    private RefreshCookieFactory underTest;

    @BeforeEach
    void setUp() {
        underTest = new RefreshCookieFactory(new AuthProperties());
    }

    @Test
    void build_setsEverySecurityAttribute() {
        ResponseCookie cookie = underTest.build("raw-token-value");

        assertEquals("relay_refresh", cookie.getName());
        assertEquals("raw-token-value", cookie.getValue());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.isSecure());
        assertEquals("Strict", cookie.getSameSite());
        assertEquals("/api/v1/auth", cookie.getPath());
        assertEquals(Duration.ofDays(30), cookie.getMaxAge());
    }

    @Test
    void build_honoursCookieSecureFalse_forNonTlsDevelopment() {
        AuthProperties insecure = new AuthProperties();
        insecure.setCookieSecure(false);

        assertEquals(false, new RefreshCookieFactory(insecure).build("v").isSecure());
    }

    @Test
    void clear_emptiesTheValueAndExpiresImmediately() {
        ResponseCookie cookie = underTest.clear();

        assertEquals("relay_refresh", cookie.getName());
        assertEquals("", cookie.getValue());
        assertEquals(Duration.ZERO, cookie.getMaxAge());
        assertEquals("/api/v1/auth", cookie.getPath());
    }
}
