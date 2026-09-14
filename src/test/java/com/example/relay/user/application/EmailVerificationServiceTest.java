package com.example.relay.user.application;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.relay.common.ratelimit.EmailVerificationRateLimiter;
import com.example.relay.email.EmailDispatchMessage;
import com.example.relay.email.EmailDispatchPublisher;
import com.example.relay.email.EmailTemplate;
import com.example.relay.user.EmailVerificationProperties;
import com.example.relay.user.domain.EmailVerificationToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EmailVerificationServiceTest {

    private UserRepository userRepository;
    private EmailVerificationTokenService emailVerificationTokenService;
    private EmailVerificationRateLimiter rateLimiter;
    private EmailDispatchPublisher emailDispatchPublisher;
    private EmailVerificationService underTest;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        emailVerificationTokenService = mock(EmailVerificationTokenService.class);
        rateLimiter = mock(EmailVerificationRateLimiter.class);
        emailDispatchPublisher = mock(EmailDispatchPublisher.class);
        EmailVerificationProperties properties = new EmailVerificationProperties();
        properties.setTokenTtl(Duration.ofHours(24));
        properties.setBaseUrl("https://example.com/verify-email");

        underTest = new EmailVerificationService(userRepository, emailVerificationTokenService, rateLimiter,
                emailDispatchPublisher, properties);
    }

    @Test
    void dispatchInitial_publishesAnEmailVerificationMessage() {
        User user = new User("dispatch@example.com", "hash");
        EmailVerificationToken token =
                new EmailVerificationToken(user, "hash", Instant.now().plusSeconds(3600), Instant.now());
        EmailVerificationTokenService.IssuedVerificationToken issued =
                new EmailVerificationTokenService.IssuedVerificationToken(token, "raw-token-value");

        underTest.dispatchInitial(user, issued);

        ArgumentCaptor<EmailDispatchMessage> captor = ArgumentCaptor.forClass(EmailDispatchMessage.class);
        verify(emailDispatchPublisher).publish(captor.capture());
        assertTrue(captor.getValue().template() == EmailTemplate.EMAIL_VERIFICATION);
        assertTrue(captor.getValue().params().get("verificationUrl").toString().contains("raw-token-value"));
        assertTrue(captor.getValue().idempotencyKey().equals(token.getId().toString()));
    }

    @Test
    void resend_doesNothing_whenRateLimited() {
        when(rateLimiter.allow("blocked@example.com", "1.2.3.4")).thenReturn(false);

        underTest.resend("blocked@example.com", "1.2.3.4");

        verify(userRepository, never()).findByEmail(any());
        verify(emailDispatchPublisher, never()).publish(any());
    }

    @Test
    void resend_doesNothing_whenNoAccountExists() {
        when(rateLimiter.allow("nobody@example.com", "1.2.3.4")).thenReturn(true);
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        underTest.resend("nobody@example.com", "1.2.3.4");

        verify(emailVerificationTokenService, never()).issue(any(), any());
        verify(emailDispatchPublisher, never()).publish(any());
    }

    @Test
    void resend_doesNothing_whenTheAccountIsAlreadyVerified() {
        User user = new User("verified@example.com", "hash");
        user.markEmailVerified();
        when(rateLimiter.allow("verified@example.com", "1.2.3.4")).thenReturn(true);
        when(userRepository.findByEmail("verified@example.com")).thenReturn(Optional.of(user));

        underTest.resend("verified@example.com", "1.2.3.4");

        verify(emailVerificationTokenService, never()).issue(any(), any());
        verify(emailDispatchPublisher, never()).publish(any());
    }

    @Test
    void resend_issuesATokenAndDispatches_whenAllowedAndUnverified() {
        User user = new User("real-user@example.com", "hash");
        EmailVerificationToken token =
                new EmailVerificationToken(user, "hash", Instant.now().plusSeconds(3600), Instant.now());
        when(rateLimiter.allow("real-user@example.com", "1.2.3.4")).thenReturn(true);
        when(userRepository.findByEmail("real-user@example.com")).thenReturn(Optional.of(user));
        when(emailVerificationTokenService.issue(any(), any()))
                .thenReturn(new EmailVerificationTokenService.IssuedVerificationToken(token, "raw-token-value"));

        underTest.resend("real-user@example.com", "1.2.3.4");

        ArgumentCaptor<EmailDispatchMessage> captor = ArgumentCaptor.forClass(EmailDispatchMessage.class);
        verify(emailDispatchPublisher).publish(captor.capture());
        assertTrue(captor.getValue().idempotencyKey().equals(token.getId().toString()));
    }

    @Test
    void verify_delegatesToConsumeAndVerify() {
        underTest.verify("raw-token");

        verify(emailVerificationTokenService).consumeAndVerify(org.mockito.ArgumentMatchers.eq("raw-token"), any());
    }
}
