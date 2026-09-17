package com.example.relay.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.application.EmailVerificationService;
import com.example.relay.user.application.EmailVerificationTokenService;
import com.example.relay.user.application.EmailVerificationTokenService.IssuedVerificationToken;
import com.example.relay.user.domain.EmailVerificationToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Mirrors EmailDispatchFailureLoggingIntegrationTest's structure (same package, same container/mocking approach) but
 * for the recoverability property rather than the log-leak property: a Brevo/EmailService failure while dispatching
 * the EMAIL_VERIFICATION message must leave the triggering token/user state untouched, and the user must still be
 * able to get a working verification email afterward via {@link EmailVerificationService#resend}. Unlike
 * PASSWORD_RESET, this template has no dispatch-confirmation column or recovery sweep (see
 * EmailDispatchConsumer's class javadoc) - resend() is this feature's only recovery path, so this is the test that
 * proves that path actually works after a failure, not just in isolation.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers
class EmailVerificationDispatchFailureIntegrationTest implements SharedPostgresContainer {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4-management");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Autowired
    private EmailVerificationTokenService emailVerificationTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private EmailService emailService;

    private User user;

    @BeforeEach
    void setUp() {
        emailVerificationTokenRepository.deleteAll();
        userRepository.deleteAll();
        user = userRepository.save(new User("verify-dispatch-failure-" + UUID.randomUUID() + "@example.com", "hash"));
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete("email-verification:cooldown:" + user.getEmail());
        emailVerificationTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void aFailedInitialDispatch_leavesStateUntouchedAndResendStillWorksAfterward() {
        Instant now = Instant.now();
        IssuedVerificationToken issued = emailVerificationTokenService.issue(user, now);
        String firstTokenId = issued.token().getId().toString();

        // Any dispatch OTHER than the first token's id (i.e. the one resend() issues below) must succeed -
        // this is what proves the recovery path actually works, not just that it doesn't throw. Mockito
        // resolves overlapping stubs by picking the most-recently-declared match, so declaring this general
        // stub BEFORE the exact-match one below lets the latter override it for the failing call only.
        when(emailService.send(eq(EmailTemplate.EMAIL_VERIFICATION), anyMap(), anyString(), anyString()))
                .thenReturn(EmailSendResult.SENT);
        when(emailService.send(eq(EmailTemplate.EMAIL_VERIFICATION), anyMap(), anyString(), eq(firstTokenId)))
                .thenThrow(new EmailSendException("Brevo send failed with status 500 INTERNAL_SERVER_ERROR", null));

        emailVerificationService.dispatchInitial(user, issued);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> verify(emailService)
                .send(eq(EmailTemplate.EMAIL_VERIFICATION), anyMap(), anyString(), eq(firstTokenId)));

        // The failed send must not have touched the triggering token or the user row at all.
        User afterFailure = userRepository.findById(user.getId()).orElseThrow();
        assertThat(afterFailure.isEmailVerified()).isFalse();
        EmailVerificationToken tokenAfterFailure =
                emailVerificationTokenRepository.findById(issued.token().getId()).orElseThrow();
        assertThat(tokenAfterFailure.getUsedAt()).isNull();
        assertThat(tokenAfterFailure.getTokenHash()).isEqualTo(issued.token().getTokenHash());

        // Recovery: the user (or a retry) calls resend() and this time the dispatch actually succeeds.
        emailVerificationService.resend(user.getEmail(), "127.0.0.1");

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> verify(emailService, times(2)).send(eq(EmailTemplate.EMAIL_VERIFICATION),
                        anyMap(), anyString(), anyString()));

        List<EmailVerificationToken> unusedTokens = emailVerificationTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .filter(token -> token.getUsedAt() == null).toList();
        assertThat(unusedTokens).hasSize(1);
        assertThat(unusedTokens.get(0).getId()).isNotEqualTo(issued.token().getId());
        assertThat(unusedTokens.get(0).getExpiresAt()).isAfter(Instant.now());
    }
}
