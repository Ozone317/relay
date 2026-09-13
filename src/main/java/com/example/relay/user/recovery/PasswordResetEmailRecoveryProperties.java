package com.example.relay.user.recovery;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@ConfigurationProperties(prefix = "relay.password-reset.email-recovery")
@Component
public class PasswordResetEmailRecoveryProperties {

    private Duration interval = Duration.ofSeconds(60);

    private Duration grace = Duration.ofSeconds(90);

    private int batchSize = 100;

    @PostConstruct
    void validate() {
        if (grace.compareTo(interval) < 0) {
            throw new IllegalStateException("relay.password-reset.email-recovery.grace (" + grace + ") must be >= "
                    + "relay.password-reset.email-recovery.interval (" + interval + "); otherwise a "
                    + "genuinely undispatched reset-link email is re-issued on every single sweep "
                    + "tick instead of once per grace period - the same reasoning as "
                    + "ReconciliationProperties.createdGrace");
        }
    }
}
