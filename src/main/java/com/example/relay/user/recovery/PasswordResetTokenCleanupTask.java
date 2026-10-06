package com.example.relay.user.recovery;

import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.common.scheduling.SchedulerNames;
import com.example.relay.common.scheduling.ScheduledCallbackRunner;
import com.example.relay.common.scheduling.ScheduledJob;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

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
    private final ScheduledCallbackRunner scheduledCallbackRunner;
    private final TransactionTemplate transactionTemplate;

    public PasswordResetTokenCleanupTask(PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordResetTokenCleanupProperties properties, ScheduledCallbackRunner scheduledCallbackRunner,
            PlatformTransactionManager transactionManager) {
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.properties = properties;
        this.scheduledCallbackRunner = scheduledCallbackRunner;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * {@code @Transactional} is required here, not optional - {@code deleteExpiredBefore} is a custom
     * {@code @Modifying @Query} method, and Spring Data JPA does not implicitly wrap those in a transaction the way it
     * does {@code SimpleJpaRepository}'s built-in CRUD methods (the same constraint {@code EmailDispatchConsumer}
     * documents and works around for {@code claimResetEmailDispatch}). Unlike that consumer, there's no external HTTP
     * call to keep out of the transaction here, so the direct-call method can use a plain method-level annotation.
     * The scheduled entry point uses an explicit {@code TransactionTemplate} inside the admitted callback body.
     */
    @Transactional
    public void cleanup() {
        cleanupOnce();
    }

    @Scheduled(fixedDelayString = "${relay.password-reset.cleanup.interval}",
            scheduler = SchedulerNames.PASSWORD_RESET_MAINTENANCE)
    public void scheduledCleanup() {
        scheduledCallbackRunner.run(ScheduledJob.PASSWORD_RESET_TOKEN_CLEANUP, properties.getInterval(),
                () -> transactionTemplate.executeWithoutResult(status -> cleanupOnce()));
    }

    private void cleanupOnce() {
        Instant threshold = Instant.now().minus(properties.getRetention());
        int deleted = passwordResetTokenRepository.deleteExpiredBefore(threshold);
        if (deleted > 0) {
            log.info("Deleted {} expired password reset token(s) older than {}", deleted, properties.getRetention());
        }
    }
}
