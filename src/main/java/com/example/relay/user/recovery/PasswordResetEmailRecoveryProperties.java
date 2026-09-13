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

    /**
     * How long, from the ORIGINAL user request, this sweeper keeps reissuing a fresh token for a user whose reset-link
     * email keeps failing to dispatch, before giving up. Bounds the whole recovery chain, not any single row - see
     * PasswordResetEmailRecoverySweeper's class javadoc for why a per-row bound wouldn't work here.
     */
    private Duration maxRecoveryWindow = Duration.ofHours(1);

    @PostConstruct
    void validate() {
        if (grace.compareTo(interval) < 0) {
            throw new IllegalStateException("relay.password-reset.email-recovery.grace (" + grace + ") must be >= "
                    + "relay.password-reset.email-recovery.interval (" + interval + "); otherwise a "
                    + "genuinely undispatched reset-link email is re-issued on every single sweep "
                    + "tick instead of once per grace period - the same reasoning as "
                    + "ReconciliationProperties.createdGrace");
        }
        Duration minimumWindow = grace.plus(interval);
        if (maxRecoveryWindow.compareTo(minimumWindow) <= 0) {
            throw new IllegalStateException(
                    "relay.password-reset.email-recovery.max-recovery-window (" + maxRecoveryWindow + ") must be > "
                            + "relay.password-reset.email-recovery.grace (" + grace + ") + "
                            + "relay.password-reset.email-recovery.interval (" + interval + ") = " + minimumWindow
                            + " - a candidate only becomes visible to the sweep once it is stale by `grace`, but "
                            + "the next tick that actually observes it can land up to a further `interval` later; "
                            + "grace alone understates the true worst-case delay before a row's first genuine "
                            + "recovery attempt, so anything <= grace + interval could give up before one ever runs");
        }
    }
}
