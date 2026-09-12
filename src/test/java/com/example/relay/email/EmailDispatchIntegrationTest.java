package com.example.relay.email;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class EmailDispatchIntegrationTest implements SharedPostgresContainer {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4-management");

    @Autowired
    private EmailDispatchPublisher publisher;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @MockitoBean
    private EmailService emailService;

    private User user;

    @BeforeEach
    void setUp() {
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();
        user = userRepository.save(new User("dispatch-test-" + UUID.randomUUID() + "@example.com", "hash"));
    }

    /**
     * passwordChangedMessage_sendsEmailAndDoesNotTouchAnyTokenRow deliberately leaves its token row with
     * resetEmailDispatchedAt/usedAt both null and expiresAt far in the future - exactly what
     * PasswordResetTokenRepositoryTest's (unscoped) dispatch-recovery finder query looks for. This is a
     * real @SpringBootTest (not @DataJpaTest), so nothing rolls it back; without this cleanup it survives in the shared
     * Postgres container for the rest of the suite and can intermittently break that other, unrelated test depending on
     * Surefire's file-order-dependent class execution order.
     */
    @AfterEach
    void tearDown() {
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void passwordResetMessage_sendsEmailAndClaimsTheTokenRow() {
        Instant now = Instant.now();
        PasswordResetToken token = passwordResetTokenRepository
                .save(new PasswordResetToken(user, "hash-" + UUID.randomUUID(), now.plusSeconds(1800), now));
        when(emailService.send(any(), anyMap(), anyString(), anyString())).thenReturn(EmailSendResult.SENT);

        publisher.publish(new EmailDispatchMessage(EmailTemplate.PASSWORD_RESET,
                Map.of("resetUrl", "https://app.relay.example/reset-password?token=raw"), user.getEmail(),
                token.getId().toString()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            PasswordResetToken reloaded = passwordResetTokenRepository.findById(token.getId()).orElseThrow();
            assertNotNull(reloaded.getResetEmailDispatchedAt());
        });
    }

    @Test
    void passwordChangedMessage_sendsEmailAndDoesNotTouchAnyTokenRow() {
        Instant now = Instant.now();
        PasswordResetToken token = passwordResetTokenRepository
                .save(new PasswordResetToken(user, "hash-" + UUID.randomUUID(), now.plusSeconds(1800), now));
        when(emailService.send(any(), anyMap(), anyString(), anyString())).thenReturn(EmailSendResult.SENT);

        publisher.publish(new EmailDispatchMessage(EmailTemplate.PASSWORD_CHANGED, Map.of(), user.getEmail(),
                token.getId().toString()));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> org.mockito.Mockito.verify(emailService).send(
                        org.mockito.ArgumentMatchers.eq(EmailTemplate.PASSWORD_CHANGED), anyMap(), anyString(),
                        anyString()));
        assertNull(passwordResetTokenRepository.findById(token.getId()).orElseThrow().getResetEmailDispatchedAt());
    }
}
