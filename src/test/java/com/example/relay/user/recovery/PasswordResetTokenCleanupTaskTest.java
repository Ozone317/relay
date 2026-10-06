package com.example.relay.user.recovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.relay.common.scheduling.ScheduledCallbackAdmission;
import com.example.relay.common.scheduling.ScheduledCallbackRunner;
import com.example.relay.common.scheduling.ScheduledJobMetrics;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

@Tag("integration")
@SpringBootTest
@Transactional
class PasswordResetTokenCleanupTaskTest implements SharedPostgresContainer {

    @Autowired
    private PasswordResetTokenCleanupTask underTest;

    @Autowired
    private PasswordResetTokenCleanupProperties properties;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
        passwordResetTokenRepository.deleteAll();
        user = userRepository.save(new User("cleanup-test-" + UUID.randomUUID() + "@example.com", "hash"));
    }

    @Test
    void cleanup_deletesOnlyRowsOlderThanTheRetentionWindow() {
        Instant now = Instant.now();
        Duration retention = properties.getRetention();

        PasswordResetToken tooOld = passwordResetTokenRepository.save(new PasswordResetToken(user, "too-old-hash",
                now.minus(retention).minusSeconds(60), now.minus(retention).minusSeconds(60)));
        PasswordResetToken withinRetention = passwordResetTokenRepository.save(new PasswordResetToken(user,
                "within-retention-hash", now.minus(retention).plusSeconds(60), now.minus(retention).plusSeconds(60)));

        underTest.cleanup();

        assertFalse(passwordResetTokenRepository.findById(tooOld.getId()).isPresent());
        assertTrue(passwordResetTokenRepository.findById(withinRetention.getId()).isPresent());
    }

    @Test
    void deniedScheduledCleanupDoesNotStartTransactionOrCallRepository() {
        PasswordResetTokenRepository repository = mock(PasswordResetTokenRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        ScheduledCallbackAdmission admission = new ScheduledCallbackAdmission(context);
        admission.onApplicationEvent(new ContextClosedEvent(context));
        ScheduledCallbackRunner runner = new ScheduledCallbackRunner(admission,
                new ScheduledJobMetrics(new SimpleMeterRegistry()));
        PasswordResetTokenCleanupTask task = new PasswordResetTokenCleanupTask(repository,
                new PasswordResetTokenCleanupProperties(), runner, transactionManager);

        task.scheduledCleanup();

        verifyNoInteractions(transactionManager, repository);
        context.close();
    }

    @Test
    void admittedScheduledCleanupRunsAtomicDeleteInsideTransactionTemplate() {
        PasswordResetTokenRepository repository = mock(PasswordResetTokenRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        when(repository.deleteExpiredBefore(any())).thenReturn(1);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        ScheduledCallbackRunner runner = new ScheduledCallbackRunner(new ScheduledCallbackAdmission(context),
                new ScheduledJobMetrics(new SimpleMeterRegistry()));
        PasswordResetTokenCleanupTask task = new PasswordResetTokenCleanupTask(repository,
                new PasswordResetTokenCleanupProperties(), runner, transactionManager);

        task.scheduledCleanup();

        verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        verify(transactionManager).commit(any());
        verify(repository).deleteExpiredBefore(any());
        context.close();
    }
}
