package com.example.relay.user.recovery;

import com.example.relay.user.application.PasswordResetService;
import com.example.relay.user.application.PasswordResetTokenService.RecoveryOutcome;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.common.scheduling.SchedulerNames;
import com.example.relay.common.scheduling.ScheduledCallbackRunner;
import com.example.relay.common.scheduling.ScheduledJob;
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
 * Unlike recoverDeadLetter(), this cannot republish the same message because the raw token is never persisted.
 * Each scan result is only a hint: PasswordResetService passes its candidate and observed owner IDs to the
 * transactional token service, which locks and rechecks that exact row before retiring it or creating a successor.
 * The original request time is carried forward from the locked candidate and the recovery-window bound is decided
 * inside that transaction.
 */
@Component
public class PasswordResetEmailRecoverySweeper {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetEmailRecoverySweeper.class);

    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordResetService passwordResetService;
    private final PasswordResetEmailRecoveryProperties properties;
    private final ScheduledCallbackRunner scheduledCallbackRunner;

    public PasswordResetEmailRecoverySweeper(PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordResetService passwordResetService, PasswordResetEmailRecoveryProperties properties,
            ScheduledCallbackRunner scheduledCallbackRunner) {
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordResetService = passwordResetService;
        this.properties = properties;
        this.scheduledCallbackRunner = scheduledCallbackRunner;
    }

    public void sweep() {
        sweepOnce();
    }

    @Scheduled(fixedDelayString = "${relay.password-reset.email-recovery.interval}",
            scheduler = SchedulerNames.PASSWORD_RESET_MAINTENANCE)
    public void scheduledSweep() {
        scheduledCallbackRunner.run(ScheduledJob.PASSWORD_RESET_EMAIL_RECOVERY, properties.getInterval(), this::sweep);
    }

    private void sweepOnce() {
        Instant now = Instant.now();
        Instant threshold = now.minus(properties.getGrace());

        List<PasswordResetToken> candidates = passwordResetTokenRepository
                .findByResetEmailDispatchedAtIsNullAndUsedAtIsNullAndExpiresAtAfterAndUpdatedAtBefore(now, threshold,
                        Limit.of(properties.getBatchSize()));

        for (PasswordResetToken candidate : candidates) {
            RecoveryOutcome outcome = passwordResetService.issueAndDispatchForRecovery(candidate.getId(),
                    candidate.getUser().getId(), properties.getGrace(), properties.getMaxRecoveryWindow());
            switch (outcome) {
                case REISSUED -> log.warn("Recovering undispatched password-reset email for user {} (stale token {})",
                        candidate.getUser().getId(), candidate.getId());
                case EXHAUSTED -> log.warn(
                        "Undispatched password-reset recovery chain retired at bound for user {} (token {})",
                        candidate.getUser().getId(), candidate.getId());
                case LOST_RACE -> log.debug("Recovery for undispatched password-reset token {} lost the race; no action needed",
                        candidate.getId());
            }
        }
    }
}
