package com.example.relay.common.security;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@ConfigurationProperties(prefix = "relay.auth")
@Component
public class AuthProperties {

    private Duration accessTokenTtl = Duration.ofMinutes(15);

    private Duration refreshIdleWindow = Duration.ofDays(30);

    private boolean cookieSecure = true;

    private String allowedOrigin = "http://localhost:3000";

    @PostConstruct
    void validate() {
        if (accessTokenTtl.compareTo(refreshIdleWindow) >= 0) {
            throw new IllegalStateException("relay.auth.access-token-ttl (" + accessTokenTtl
                    + ") must be < relay.auth.refresh-idle-window (" + refreshIdleWindow
                    + "); otherwise the access token outlives the session's idle window, making "
                    + "the refresh mechanism pointless and letting a session outlive its own credential");
        }
    }
}
