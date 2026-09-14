package com.example.relay.user;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Data
@ConfigurationProperties(prefix = "relay.email-verification")
@Component
public class EmailVerificationProperties {

    private Duration tokenTtl = Duration.ofHours(24);

    /**
     * The frontend's verify-email page origin, e.g. "https://app.relay.example/verify-email". Trusted configuration,
     * never constructed from an incoming request's Host header - same reasoning as PasswordResetProperties.baseUrl.
     */
    private String baseUrl;

    @PostConstruct
    void validate() {
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException(
                    "relay.email-verification.base-url is required - verification emails cannot link "
                            + "anywhere without it");
        }
    }
}
