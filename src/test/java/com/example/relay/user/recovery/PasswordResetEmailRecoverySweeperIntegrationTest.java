package com.example.relay.user.recovery;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.relay.email.EmailSendResult;
import com.example.relay.email.EmailService;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {"relay.password-reset.email-recovery.interval=2s",
        "relay.password-reset.email-recovery.grace=2s"})
// This class's fast 2s-interval sweeper runs in a dedicated Spring context (distinct properties from
// every other @SpringBootTest class), which Spring's test-context cache would otherwise keep alive -
// and its @Scheduled thread running - for the rest of the suite once cached. Left uncontained, that
// stray sweeper sees the ENTIRE shared Postgres database (see SharedPostgresContainer) and recovers
// any stale password_reset_tokens row left behind by unrelated tests, inserting a fresh token that
// then blocks those tests' own user cleanup via password_reset_tokens_user_id_fkey. AFTER_CLASS
// forces this context (and its scheduler) to close once this class's tests finish - the same fix
// AttemptServiceMarkFailedAndCreateRetryAtomicityTest/AuthServiceRegisterAtomicityTest/
// UnhandledExceptionIntegrationTest already apply for their own non-default contexts.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordResetEmailRecoverySweeperIntegrationTest implements SharedPostgresContainer {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4-management");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @MockitoBean
    private EmailService emailService;

    private User user;
    private PasswordResetToken staleToken;

    @BeforeEach
    void setUp() {
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();

        user = userRepository.save(new User("recovery-sweeper-test@example.com", "hash"));
        Instant longAgo = Instant.now().minusSeconds(120);
        staleToken = passwordResetTokenRepository
                .save(new PasswordResetToken(user, "stale-hash", longAgo.plusSeconds(1800), longAgo));

        when(emailService.send(any(), anyMap(), anyString(), anyString())).thenReturn(EmailSendResult.SENT);
    }

    @Test
    void undispatchedToken_isRecovered_byIssuingAFreshTokenAndInvalidatingTheStaleOne() {
        AtomicInteger sendCount = new AtomicInteger(0);
        when(emailService.send(any(), anyMap(), anyString(), anyString())).thenAnswer(invocation -> {
            sendCount.incrementAndGet();
            return EmailSendResult.SENT;
        });

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            PasswordResetToken reloadedStale = passwordResetTokenRepository.findById(staleToken.getId()).orElseThrow();
            assertNotNull(reloadedStale.getUsedAt(), "the stale row must be invalidated by the recovery issuance");
            assertNull(reloadedStale.getResetEmailDispatchedAt(),
                    "the stale row itself was never dispatched - only a NEW row's email gets sent");
        });
    }

    @Test
    void aTokenPastExpiry_isNeverPickedUpBySweep() throws InterruptedException {
        passwordResetTokenRepository.deleteAll();
        Instant longAgo = Instant.now().minusSeconds(300);
        PasswordResetToken expired =
                passwordResetTokenRepository.save(new PasswordResetToken(user, "expired-hash", longAgo, longAgo));

        Thread.sleep(5000);

        PasswordResetToken reloaded = passwordResetTokenRepository.findById(expired.getId()).orElseThrow();
        assertNull(reloaded.getUsedAt(), "an already-expired token must never be touched by recovery");
    }
}
