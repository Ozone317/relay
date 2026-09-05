package com.example.relay.common.security;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshCookieFactory {

    public static final String COOKIE_NAME = "relay_refresh";

    private static final String COOKIE_PATH = "/api/v1/auth";

    private final AuthProperties authProperties;

    public RefreshCookieFactory(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    public ResponseCookie build(String rawToken) {
        return base(rawToken).maxAge(authProperties.getRefreshIdleWindow()).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(COOKIE_NAME, value).httpOnly(true).secure(authProperties.isCookieSecure())
                .sameSite("Strict").path(COOKIE_PATH);
    }
}
