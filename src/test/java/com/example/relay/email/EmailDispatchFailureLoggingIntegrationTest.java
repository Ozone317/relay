package com.example.relay.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Covers design spec Section 12: the raw reset token is embedded in the resetUrl that rides as the PASSWORD_RESET
 * RabbitMQ message body, so any failure-handling path that logs a message (Spring AMQP's container error handling in
 * particular) is a real channel for the raw token to leak into logs. This forces EmailService.send to fail for a
 * PASSWORD_RESET dispatch and asserts the raw token never appears in any log line captured across the whole
 * failure-handling path, root logger included.
 *
 * <p>
 * spring.rabbitmq.listener.simple.default-requeue-rejected=false (application.properties) means a failed delivery is
 * rejected without requeue rather than retried forever, so this failure path runs exactly once per publish - no
 * separate bound is needed here to keep the test finite.
 */
@SpringBootTest
@Testcontainers
class EmailDispatchFailureLoggingIntegrationTest implements SharedPostgresContainer {

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
    private ListAppender<ILoggingEvent> rootAppender;

    @BeforeEach
    void setUp() {
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();
        user = userRepository.save(new User("failure-log-test-" + UUID.randomUUID() + "@example.com", "hash"));

        rootAppender = new ListAppender<>();
        rootAppender.start();
        rootLogger().addAppender(rootAppender);
    }

    @AfterEach
    void tearDown() {
        rootLogger().detachAppender(rootAppender);
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    private Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    @Test
    void rawTokenNeverAppearsInLogs_whenPasswordResetDispatchFails() {
        Instant now = Instant.now();
        PasswordResetToken token = passwordResetTokenRepository
                .save(new PasswordResetToken(user, "hash-" + UUID.randomUUID(), now.plusSeconds(1800), now));
        String rawToken = "raw-reset-token-" + UUID.randomUUID();
        String resetUrl = "https://app.relay.example/reset-password?token=" + rawToken;

        when(emailService.send(eq(EmailTemplate.PASSWORD_RESET), anyMap(), anyString(), anyString()))
                .thenThrow(new EmailSendException("Brevo send failed with status 500 INTERNAL_SERVER_ERROR", null));

        publisher.publish(new EmailDispatchMessage(EmailTemplate.PASSWORD_RESET, Map.of("resetUrl", resetUrl),
                user.getEmail(), token.getId().toString()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> verify(emailService)
                .send(eq(EmailTemplate.PASSWORD_RESET), anyMap(), anyString(), eq(token.getId().toString())));

        // Give the container's error-handling path (log statements emitted after the listener throws) a
        // moment to run before asserting over the captured events.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(rootAppender.list).isNotEmpty());

        assertThat(rootAppender.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains(rawToken));
        assertThat(rootAppender.list).flatExtracting(event -> {
            Object[] args = event.getArgumentArray();
            return args == null ? java.util.List.of() : java.util.List.of(args);
        }).noneMatch(arg -> String.valueOf(arg).contains(rawToken));
    }
}
