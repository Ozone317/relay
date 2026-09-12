package com.example.relay.user.application;

import com.example.relay.common.ratelimit.PasswordResetRateLimiter;
import com.example.relay.email.EmailDispatchMessage;
import com.example.relay.email.EmailDispatchPublisher;
import com.example.relay.email.EmailTemplate;
import com.example.relay.user.PasswordResetProperties;
import com.example.relay.user.application.PasswordResetTokenService.IssuedResetToken;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Deliberately NOT @Transactional at this level - it does non-DB work (rate limiting) before, and needs to publish an
 * email message only AFTER PasswordResetTokenService's transactional methods have genuinely committed, which requires
 * calling a distinct bean through Spring's AOP proxy, not a method on this same instance.
 */
@Service
public class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenService passwordResetTokenService;
    private final PasswordResetRateLimiter rateLimiter;
    private final EmailDispatchPublisher emailDispatchPublisher;
    private final PasswordResetProperties passwordResetProperties;

    public PasswordResetService(UserRepository userRepository, PasswordResetTokenService passwordResetTokenService,
            PasswordResetRateLimiter rateLimiter, EmailDispatchPublisher emailDispatchPublisher,
            PasswordResetProperties passwordResetProperties) {
        this.userRepository = userRepository;
        this.passwordResetTokenService = passwordResetTokenService;
        this.rateLimiter = rateLimiter;
        this.emailDispatchPublisher = emailDispatchPublisher;
        this.passwordResetProperties = passwordResetProperties;
    }

    /**
     * Always completes with no externally-observable difference between "email does not exist", "rate limited", and
     * "email sent" - see the design spec Section 3 for why that must never change.
     */
    public void requestReset(String email, String clientIp) {
        if (!rateLimiter.allow(email, clientIp)) {
            return;
        }
        userRepository.findByEmail(email).ifPresent(this::issueAndDispatch);
    }

    /**
     * Issues a fresh token for this user and dispatches its reset-link email. Also called directly by
     * com.example.relay.user.recovery.PasswordResetEmailRecoverySweeper for a row whose original dispatch was never
     * confirmed - recovery here means re-running this same issuance, not resending the original message, because the
     * raw token is never persisted and so cannot be reconstructed from the database (see the design spec Section 7.2).
     */
    public void issueAndDispatch(User user) {
        IssuedResetToken issued = passwordResetTokenService.issue(user, Instant.now());
        PasswordResetToken token = issued.token();
        String resetUrl = passwordResetProperties.getBaseUrl() + "?token=" + issued.rawToken();

        emailDispatchPublisher.publish(new EmailDispatchMessage(EmailTemplate.PASSWORD_RESET,
                Map.of("resetUrl", resetUrl), user.getEmail(), token.getId().toString()));
    }

    public void confirmReset(String rawToken, String newPassword) {
        PasswordResetToken token =
                passwordResetTokenService.consumeAndResetPassword(rawToken, newPassword, Instant.now());

        emailDispatchPublisher.publish(new EmailDispatchMessage(EmailTemplate.PASSWORD_CHANGED, Map.of(),
                token.getUser().getEmail(), token.getId().toString()));
    }
}
