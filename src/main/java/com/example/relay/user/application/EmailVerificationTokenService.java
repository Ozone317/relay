package com.example.relay.user.application;

import com.example.relay.common.security.SecureTokenGenerator;
import com.example.relay.user.EmailVerificationProperties;
import com.example.relay.user.domain.EmailVerificationToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.InvalidOrExpiredVerificationTokenException;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the two transactional DB operations for email verification, mirroring PasswordResetTokenService's relationship
 * to PasswordResetService exactly.
 */
@Service
public class EmailVerificationTokenService {

    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final UserRepository userRepository;
    private final SecureTokenGenerator secureTokenGenerator;
    private final EmailVerificationProperties emailVerificationProperties;

    public EmailVerificationTokenService(EmailVerificationTokenRepository emailVerificationTokenRepository,
            UserRepository userRepository, SecureTokenGenerator secureTokenGenerator,
            EmailVerificationProperties emailVerificationProperties) {
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.userRepository = userRepository;
        this.secureTokenGenerator = secureTokenGenerator;
        this.emailVerificationProperties = emailVerificationProperties;
    }

    /**
     * Invalidates every previously-unused token for this user, then issues a new one - both in one transaction, same
     * "supersede, don't leave orphaned state" shape as PasswordResetTokenService.issue.
     */
    @Transactional
    public IssuedVerificationToken issue(User user, Instant now) {
        emailVerificationTokenRepository.invalidateAllForUser(user.getId(), now);
        String rawToken = secureTokenGenerator.generateRawToken();
        EmailVerificationToken token = emailVerificationTokenRepository.saveAndFlush(new EmailVerificationToken(user,
                secureTokenGenerator.hash(rawToken), now.plus(emailVerificationProperties.getTokenTtl()), now));
        return new IssuedVerificationToken(token, rawToken);
    }

    /**
     * The atomic consume UPDATE's row count is the sole authority on token validity - not any prior SELECT. The consume
     * and the user's email_verified=true write happen in this one transaction: they can never split across a partial
     * failure.
     */
    @Transactional
    public EmailVerificationToken consumeAndVerify(String rawToken, Instant now) {
        String tokenHash = secureTokenGenerator.hash(rawToken);
        if (emailVerificationTokenRepository.consume(tokenHash, now) == 0) {
            throw new InvalidOrExpiredVerificationTokenException(
                    "Verification token was not found, has already been used, or has expired");
        }

        EmailVerificationToken token = emailVerificationTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new IllegalStateException("Token hash " + tokenHash + " was consumed but not found "
                        + "immediately after within the same transaction - should be impossible"));

        User user = token.getUser();
        user.markEmailVerified();
        userRepository.save(user);

        return token;
    }

    public record IssuedVerificationToken(EmailVerificationToken token, String rawToken) {
    }
}
