package com.example.relay.user.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.relay.email.EmailSendResult;
import com.example.relay.email.EmailService;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.support.background.EnableTestBackgroundExecution;
import com.example.relay.support.background.TestBackgroundComponent;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {"relay.password-reset.email-recovery.interval=2s",
        "relay.password-reset.email-recovery.grace=2s", "relay.password-reset.email-recovery.max-recovery-window=1h"})
@EnableTestBackgroundExecution({TestBackgroundComponent.SCHEDULING, TestBackgroundComponent.RABBIT_LISTENERS})
class PasswordResetEmailRecoverySweeperIntegrationTest implements SharedPostgresContainer {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4.3.6-management");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @MockitoBean
    private EmailService emailService;

    @MockitoSpyBean
    private PasswordResetEmailRecoverySweeper sweeper;

    private User user;
    private PasswordResetToken staleToken;
    private ListAppender<ILoggingEvent> sweeperLogAppender;
    private AtomicInteger startedSweeps;
    private AtomicInteger completedSweeps;

    @BeforeEach
    void setUp() {
        passwordResetTokenRepository.deleteAll();

        user = userRepository.save(new User("recovery-sweeper-" + UUID.randomUUID() + "@example.com", "hash"));

        when(emailService.send(any(), anyMap(), anyString(), anyString())).thenReturn(EmailSendResult.SENT);

        startedSweeps = new AtomicInteger();
        completedSweeps = new AtomicInteger();
        doAnswer(invocation -> {
                    int invocationNumber = startedSweeps.incrementAndGet();
                    try {
                        return invocation.callRealMethod();
                    } finally {
                        completedSweeps.accumulateAndGet(invocationNumber, Math::max);
                    }
                })
                .when(sweeper)
                .sweep();

        sweeperLogAppender = new ListAppender<>();
        sweeperLogAppender.start();
        sweeperLogger().addAppender(sweeperLogAppender);
    }

    @AfterEach
    void tearDown() {
        sweeperLogger().detachAppender(sweeperLogAppender);
    }

    private Logger sweeperLogger() {
        return (Logger) LoggerFactory.getLogger(PasswordResetEmailRecoverySweeper.class);
    }

    @Test
    void undispatchedToken_isRecovered_byIssuingAFreshTokenAndInvalidatingTheStaleOne() {
        Instant longAgo = Instant.now().minusSeconds(120);
        staleToken = passwordResetTokenRepository.save(
                new PasswordResetToken(user, "stale-" + UUID.randomUUID(), longAgo.plusSeconds(1800), longAgo));

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

            List<PasswordResetToken> successors = passwordResetTokenRepository.findAll().stream()
                    .filter(token -> !token.getId().equals(staleToken.getId()))
                    .toList();
            assertEquals(1, successors.size(), "recovery must issue exactly one successor token");
            assertNotNull(successors.getFirst().getResetEmailDispatchedAt(),
                    "the Rabbit consumer must confirm dispatch for the successor token");
            assertEquals(1, sendCount.get(), "recovery must send exactly one reset email");
        });
    }

    @Test
    void tokenPastMaxRecoveryWindow_isGivenUpOn_withNoSuccessorRowReissued() {
        passwordResetTokenRepository.deleteAll();
        Instant staleSince = Instant.now().minusSeconds(120);
        Instant longPastFirstRequest = Instant.now().minusSeconds(3600 + 120);
        PasswordResetToken pastWindow = passwordResetTokenRepository.save(new PasswordResetToken(user,
                "past-window-hash", staleSince.plusSeconds(1800), staleSince, longPastFirstRequest));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            PasswordResetToken reloaded = passwordResetTokenRepository.findById(pastWindow.getId()).orElseThrow();
            assertNotNull(reloaded.getUsedAt(), "a chain past its recovery window must be given up on");
        });

        assertEquals(1, passwordResetTokenRepository.count(),
                "giving up must retire the row in place, never reissue a successor");
        assertThat(sweeperLogAppender.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(message -> message.contains("Giving up"));
    }

    @Test
    void aTokenPastExpiry_isNeverPickedUpBySweep() {
        passwordResetTokenRepository.deleteAll();
        Instant longAgo = Instant.now().minusSeconds(300);
        PasswordResetToken expired =
                passwordResetTokenRepository.save(new PasswordResetToken(user, "expired-hash", longAgo, longAgo));

        int requiredPostInsertSweep = startedSweeps.get() + 1;
        await().atMost(Duration.ofSeconds(15))
                .until(() -> completedSweeps.get() >= requiredPostInsertSweep);

        PasswordResetToken reloaded = passwordResetTokenRepository.findById(expired.getId()).orElseThrow();
        assertNull(reloaded.getUsedAt(), "an already-expired token must never be touched by recovery");
    }
}
