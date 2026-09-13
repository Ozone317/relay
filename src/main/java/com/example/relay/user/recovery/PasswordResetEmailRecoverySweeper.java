package com.example.relay.user.recovery;

import com.example.relay.user.application.PasswordResetService;
import com.example.relay.user.application.PasswordResetTokenService;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recovers a password_reset_tokens row whose reset-link email was never confirmed dispatched. Deliberately its own
 * component, not folded into deliveryengine.ReconciliationSweeper, which is scoped to that package's own concern.
 *
 * <p>
 * Unlike recoverDeadLetter(), this cannot "republish the same message" - the raw token is never persisted (see
 * PasswordResetTokenRepository/PasswordResetToken), so there is nothing in the database to reconstruct a lost email
 * from. Recovery instead re-runs the issuance flow for the affected user (a fresh token, a fresh raw value, a fresh
 * email) via PasswordResetService.issueAndDispatchForRecovery - the same thing that happens if the user simply clicks
 * "forgot password" again, except {@code firstRequestedAt} is carried forward from the row being superseded rather than
 * reset to {@code now}. That call's own PasswordResetTokenService.reissueForRecovery(...) invalidates this stale row as
 * an intrinsic side effect (sets used_at), which is what removes it from this sweeper's candidate query on the very
 * next tick - no separate touch/staleness-bump guard is needed.
 *
 * <p>
 * Bounded retry: because recovery here mints a brand-new token row (fresh id, fresh expires_at, fresh updated_at)
 * rather than resending the original message, a per-row bound (like a simple attempt counter) wouldn't mean anything on
 * its own - a fresh row always starts at zero. Instead, {@code firstRequestedAt} - the ORIGINAL user request's
 * timestamp, carried forward through every reissue - is compared against
 * {@link PasswordResetEmailRecoveryProperties#getMaxRecoveryWindow()}. Once a chain has been unrecovered for longer
 * than that window, this sweeper gives up: it retires the row in place (used_at set, via
 * PasswordResetTokenRepository#giveUpOn) and logs a WARN, rather than reissuing yet again. Giving up removes the row
 * from this sweeper's candidate query immediately, the same way a successful reissue does - so this fires at most once
 * per chain, not once per tick for the rest of the row's natural expires_at lifetime.
 */
@Component
public class PasswordResetEmailRecoverySweeper {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetEmailRecoverySweeper.class);

    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordResetService passwordResetService;
    private final PasswordResetTokenService passwordResetTokenService;
    private final PasswordResetEmailRecoveryProperties properties;

    public PasswordResetEmailRecoverySweeper(PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordResetService passwordResetService, PasswordResetTokenService passwordResetTokenService,
            PasswordResetEmailRecoveryProperties properties) {
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordResetService = passwordResetService;
        this.passwordResetTokenService = passwordResetTokenService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${relay.password-reset.email-recovery.interval}")
    public void sweep() {
        Instant now = Instant.now();
        Instant threshold = now.minus(properties.getGrace());

        List<PasswordResetToken> candidates = passwordResetTokenRepository
                .findByResetEmailDispatchedAtIsNullAndUsedAtIsNullAndExpiresAtAfterAndUpdatedAtBefore(now, threshold,
                        Limit.of(properties.getBatchSize()));

        for (PasswordResetToken candidate : candidates) {
            Duration elapsedSinceFirstRequest = Duration.between(candidate.getFirstRequestedAt(), now);
            if (elapsedSinceFirstRequest.compareTo(properties.getMaxRecoveryWindow()) >= 0) {
                boolean gaveUp = passwordResetTokenService.giveUpOnRecovery(candidate.getId(), now);
                if (gaveUp) {
                    log.warn(
                            "Giving up on undispatched password-reset email for user {} (token {}) - unrecovered "
                                    + "for {}, past the configured max-recovery-window of {}",
                            candidate.getUser().getId(), candidate.getId(), elapsedSinceFirstRequest,
                            properties.getMaxRecoveryWindow());
                } else {
                    log.warn("Give-up for undispatched password-reset token {} lost the race (already claimed by "
                            + "a dispatch confirmation or a user's own consume call) - no action needed",
                            candidate.getId());
                }
            } else {
                log.warn("Recovering undispatched password-reset email for user {} (stale token {})",
                        candidate.getUser().getId(), candidate.getId());
                passwordResetService.issueAndDispatchForRecovery(candidate.getUser(), candidate.getFirstRequestedAt());
            }
        }
    }
}
