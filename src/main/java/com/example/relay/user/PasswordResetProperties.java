package com.example.relay.user;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Data
@ConfigurationProperties(prefix = "relay.password-reset")
@Component
public class PasswordResetProperties {

    private Duration tokenTtl = Duration.ofMinutes(30);

    /**
     * The frontend's reset-password page origin, e.g. "https://app.relay.example/reset-password". Trusted
     * configuration, never constructed from an incoming request's Host header - an attacker-influenced base URL in a
     * password-reset email is a direct account-takeover vector.
     */
    private String baseUrl;

    @PostConstruct
    void validate() {
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException(
                    "relay.password-reset.base-url is required - password-reset emails cannot link "
                            + "anywhere without it");
        }
    }
}
