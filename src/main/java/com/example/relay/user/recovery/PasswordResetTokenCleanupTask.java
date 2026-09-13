package com.example.relay.user.recovery;

import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes reset-token rows that are both expired and long past any plausible recovery-sweep interest - a token this old
 * could not still be a candidate for PasswordResetEmailRecoverySweeper (that sweeper only ever considers rows with
 * expires_at in the future). Keeps a bounded retention window rather than deleting immediately on expiry/use, in case
 * bounded history is useful for debugging.
 */
@Component
public class PasswordResetTokenCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetTokenCleanupTask.class);

    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordResetTokenCleanupProperties properties;

    public PasswordResetTokenCleanupTask(PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordResetTokenCleanupProperties properties) {
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${relay.password-reset.cleanup.interval}")
    public void cleanup() {
        Instant threshold = Instant.now().minus(properties.getRetention());
        int deleted = passwordResetTokenRepository.deleteExpiredBefore(threshold);
        if (deleted > 0) {
            log.info("Deleted {} expired password reset token(s) older than {}", deleted, properties.getRetention());
        }
    }
}
