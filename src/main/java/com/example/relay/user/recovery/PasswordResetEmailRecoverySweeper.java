package com.example.relay.user.recovery;

import com.example.relay.user.application.PasswordResetService;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
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
 * email) via PasswordResetService.issueAndDispatch - the same thing that happens if the user simply clicks "forgot
 * password" again. That call's own PasswordResetTokenService.issue(...) invalidates this stale row as an intrinsic side
 * effect (sets used_at), which is what removes it from this sweeper's candidate query on the very next tick - no
 * separate touch/staleness-bump guard is needed.
 */
@Component
public class PasswordResetEmailRecoverySweeper {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetEmailRecoverySweeper.class);

    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordResetService passwordResetService;
    private final PasswordResetEmailRecoveryProperties properties;

    public PasswordResetEmailRecoverySweeper(PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordResetService passwordResetService, PasswordResetEmailRecoveryProperties properties) {
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordResetService = passwordResetService;
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
            log.warn("Recovering undispatched password-reset email for user {} (stale token {})",
                    candidate.getUser().getId(), candidate.getId());
            passwordResetService.issueAndDispatch(candidate.getUser());
        }
    }
}
