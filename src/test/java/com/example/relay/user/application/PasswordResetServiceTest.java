package com.example.relay.user.application;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.relay.common.ratelimit.PasswordResetRateLimiter;
import com.example.relay.email.EmailDispatchMessage;
import com.example.relay.email.EmailDispatchPublisher;
import com.example.relay.email.EmailTemplate;
import com.example.relay.user.PasswordResetProperties;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PasswordResetServiceTest {

    private UserRepository userRepository;
    private PasswordResetTokenService passwordResetTokenService;
    private PasswordResetRateLimiter rateLimiter;
    private EmailDispatchPublisher emailDispatchPublisher;
    private PasswordResetService underTest;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordResetTokenService = mock(PasswordResetTokenService.class);
        rateLimiter = mock(PasswordResetRateLimiter.class);
        emailDispatchPublisher = mock(EmailDispatchPublisher.class);
        PasswordResetProperties properties = new PasswordResetProperties();
        properties.setTokenTtl(Duration.ofMinutes(30));
        properties.setBaseUrl("https://example.com/reset-password");

        underTest = new PasswordResetService(userRepository, passwordResetTokenService, rateLimiter,
                emailDispatchPublisher, properties);
    }

    @Test
    void requestReset_doesNothing_whenRateLimited() {
        when(rateLimiter.allow("blocked@example.com", "1.2.3.4")).thenReturn(false);

        underTest.requestReset("blocked@example.com", "1.2.3.4");

        verify(userRepository, never()).findByEmail(any());
        verify(emailDispatchPublisher, never()).publish(any());
    }

    @Test
    void requestReset_doesNothing_whenNoAccountExists() {
        when(rateLimiter.allow("nobody@example.com", "1.2.3.4")).thenReturn(true);
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        underTest.requestReset("nobody@example.com", "1.2.3.4");

        verify(passwordResetTokenService, never()).issue(any(), any());
        verify(emailDispatchPublisher, never()).publish(any());
    }

    @Test
    void requestReset_issuesATokenAndDispatchesTheResetEmail_whenAllowedAndAccountExists() {
        User user = new User("real-user@example.com", "hash");
        PasswordResetToken token = new PasswordResetToken(user, "hash", java.time.Instant.now().plusSeconds(1800),
                java.time.Instant.now());
        when(rateLimiter.allow("real-user@example.com", "1.2.3.4")).thenReturn(true);
        when(userRepository.findByEmail("real-user@example.com")).thenReturn(Optional.of(user));
        when(passwordResetTokenService.issue(any(), any()))
                .thenReturn(new PasswordResetTokenService.IssuedResetToken(token, "raw-token-value"));

        underTest.requestReset("real-user@example.com", "1.2.3.4");

        org.mockito.ArgumentCaptor<EmailDispatchMessage> captor =
                org.mockito.ArgumentCaptor.forClass(EmailDispatchMessage.class);
        verify(emailDispatchPublisher).publish(captor.capture());
        EmailDispatchMessage published = captor.getValue();
        assertTrue(published.template() == EmailTemplate.PASSWORD_RESET);
        assertTrue(published.params().get("resetUrl").toString().contains("raw-token-value"));
        assertTrue(published.idempotencyKey().equals(token.getId().toString()));
    }

    @Test
    void confirmReset_dispatchesThePasswordChangedNotification() {
        User user = new User("confirm-user@example.com", "hash");
        PasswordResetToken token = new PasswordResetToken(user, "hash", java.time.Instant.now().plusSeconds(1800),
                java.time.Instant.now());
        when(passwordResetTokenService.consumeAndResetPassword(any(), any(), any())).thenReturn(token);

        underTest.confirmReset("raw-token", "newPassword123");

        org.mockito.ArgumentCaptor<EmailDispatchMessage> captor =
                org.mockito.ArgumentCaptor.forClass(EmailDispatchMessage.class);
        verify(emailDispatchPublisher).publish(captor.capture());
        assertTrue(captor.getValue().template() == EmailTemplate.PASSWORD_CHANGED);
    }

    @Test
    void confirmReset_usesAnIdempotencyKeyDistinctFromTheResetDispatchForTheSameToken() {
        User user = new User("real-user@example.com", "hash");
        PasswordResetToken token = new PasswordResetToken(user, "hash", java.time.Instant.now().plusSeconds(1800),
                java.time.Instant.now());
        when(rateLimiter.allow("real-user@example.com", "1.2.3.4")).thenReturn(true);
        when(userRepository.findByEmail("real-user@example.com")).thenReturn(Optional.of(user));
        when(passwordResetTokenService.issue(any(), any()))
                .thenReturn(new PasswordResetTokenService.IssuedResetToken(token, "raw-token-value"));
        when(passwordResetTokenService.consumeAndResetPassword(any(), any(), any())).thenReturn(token);

        underTest.requestReset("real-user@example.com", "1.2.3.4");
        underTest.confirmReset("raw-token-value", "newPassword123");

        org.mockito.ArgumentCaptor<EmailDispatchMessage> captor =
                org.mockito.ArgumentCaptor.forClass(EmailDispatchMessage.class);
        verify(emailDispatchPublisher, org.mockito.Mockito.times(2)).publish(captor.capture());

        java.util.List<EmailDispatchMessage> published = captor.getAllValues();
        EmailDispatchMessage resetMessage = published.get(0);
        EmailDispatchMessage changedMessage = published.get(1);

        assertTrue(resetMessage.template() == EmailTemplate.PASSWORD_RESET);
        assertTrue(changedMessage.template() == EmailTemplate.PASSWORD_CHANGED);
        assertTrue(resetMessage.idempotencyKey().equals(token.getId().toString()));
        assertTrue(!changedMessage.idempotencyKey().equals(resetMessage.idempotencyKey()));
        // Deterministic - the same token must always derive the same PASSWORD_CHANGED key, so that RabbitMQ
        // redelivery of the same message still dedupes at Brevo instead of sending a second alert.
        assertTrue(changedMessage.idempotencyKey()
                .equals(java.util.UUID
                        .nameUUIDFromBytes(
                                ("password-changed:" + token.getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString()));
        // Must still pass BrevoEmailSender's UUID.fromString(...) validation.
        assertTrue(java.util.UUID.fromString(changedMessage.idempotencyKey()) != null);
    }
}
