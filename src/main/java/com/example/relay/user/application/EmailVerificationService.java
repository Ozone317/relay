package com.example.relay.user.application;

import com.example.relay.common.ratelimit.EmailVerificationRateLimiter;
import com.example.relay.email.EmailDispatchMessage;
import com.example.relay.email.EmailDispatchPublisher;
import com.example.relay.email.EmailTemplate;
import com.example.relay.user.EmailVerificationProperties;
import com.example.relay.user.application.EmailVerificationTokenService.IssuedVerificationToken;
import com.example.relay.user.domain.EmailVerificationToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Deliberately NOT @Transactional, matching PasswordResetService's own reasoning: it does non-DB work (rate limiting,
 * publishing) and must call EmailVerificationTokenService's transactional methods through the Spring proxy (a distinct
 * bean), not via self-invocation.
 *
 * <p>
 * resend() is the ONLY reissue path in this feature - AuthService.register()'s handling of an existing-unverified
 * email (Task 13) calls this exact method via AuthController (Task 14), rather than a second, register-specific
 * reissue method. See the design spec Section 5 for why an earlier draft's separate reissue method was wrong (it ran
 * Redis/RabbitMQ calls inside AuthService.register()'s open transaction).
 */
@Service
public class EmailVerificationService {

    private final UserRepository userRepository;
    private final EmailVerificationTokenService emailVerificationTokenService;
    private final EmailVerificationRateLimiter rateLimiter;
    private final EmailDispatchPublisher emailDispatchPublisher;
    private final EmailVerificationProperties emailVerificationProperties;

    public EmailVerificationService(UserRepository userRepository,
            EmailVerificationTokenService emailVerificationTokenService, EmailVerificationRateLimiter rateLimiter,
            EmailDispatchPublisher emailDispatchPublisher, EmailVerificationProperties emailVerificationProperties) {
        this.userRepository = userRepository;
        this.emailVerificationTokenService = emailVerificationTokenService;
        this.rateLimiter = rateLimiter;
        this.emailDispatchPublisher = emailDispatchPublisher;
        this.emailVerificationProperties = emailVerificationProperties;
    }

    /**
     * Dispatches the FIRST verification email for a just-registered user. Called by AuthController.register() after
     * AuthService.register()'s transaction has committed - never from inside it. Takes the full IssuedVerificationToken
     * (not just the raw string) so the token row's id is available for the idempotency key without a second lookup.
     */
    public void dispatchInitial(User user, IssuedVerificationToken issued) {
        dispatch(issued, user);
    }

    /**
     * Always completes with no externally-observable difference between "email does not exist", "rate limited",
     * "already verified", and "email sent" - see the design spec Section 8.
     */
    public void resend(String email, String clientIp) {
        if (!rateLimiter.allow(email, clientIp)) {
            return;
        }
        userRepository.findByEmail(email).filter(user -> !user.isEmailVerified())
                .ifPresent(user -> dispatch(emailVerificationTokenService.issue(user, Instant.now()), user));
    }

    /**
     * Consuming the token is also what sets the account's real password - see
     * EmailVerificationTokenService.consumeAndVerify for the security property this provides.
     */
    public void verify(String rawToken, String password) {
        emailVerificationTokenService.consumeAndVerify(rawToken, password, Instant.now());
    }

    private void dispatch(IssuedVerificationToken issued, User user) {
        EmailVerificationToken token = issued.token();
        String verificationUrl = emailVerificationProperties.getBaseUrl() + "?token=" + issued.rawToken();

        emailDispatchPublisher.publish(new EmailDispatchMessage(EmailTemplate.EMAIL_VERIFICATION,
                Map.of("verificationUrl", verificationUrl), user.getEmail(), token.getId().toString()));
    }
}
