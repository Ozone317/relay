package com.example.relay.user.application;

import com.example.relay.common.security.SecureTokenGenerator;
import com.example.relay.user.EmailVerificationProperties;
import com.example.relay.user.domain.EmailVerificationToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.InvalidOrExpiredVerificationTokenException;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    private final PasswordEncoder passwordEncoder;

    public EmailVerificationTokenService(EmailVerificationTokenRepository emailVerificationTokenRepository,
            UserRepository userRepository, SecureTokenGenerator secureTokenGenerator,
            EmailVerificationProperties emailVerificationProperties, PasswordEncoder passwordEncoder) {
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.userRepository = userRepository;
        this.secureTokenGenerator = secureTokenGenerator;
        this.emailVerificationProperties = emailVerificationProperties;
        this.passwordEncoder = passwordEncoder;
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
     * Consumes the one currently-valid verification token AND sets the account's real password, atomically.
     *
     * <p>
     * The atomic consume UPDATE's row count is the sole authority on token validity - not any prior SELECT. The
     * consume, the password write and the user's email_verified=true write all happen in this one transaction: they
     * can never split across a partial failure.
     *
     * <p>
     * Security property (this is what closes the account pre-hijacking vulnerability): the password submitted HERE -
     * not whatever was submitted to /register, however many times, by whomever - is the one that becomes live. Setting
     * it is atomic with proving control of the mailbox, because the raw token is only ever delivered to the account's
     * own email address, and {@link #issue(User, Instant)} invalidates every previous token so exactly one is valid at
     * a time. Consequently no unauthenticated register() call can ever determine an account's final password; only
     * whoever successfully consumes the currently-valid token can.
     *
     * <p>
     * A dangling-but-still-valid token can exist for an already-verified account (a documented, harmless race between
     * this method and a concurrent {@link #issue(User, Instant)}/resend). Consuming such a token is rejected here -
     * once a user is verified, no further token consumption may apply a password to that account.
     */
    @Transactional
    public EmailVerificationToken consumeAndVerify(String rawToken, String password, Instant now) {
        String tokenHash = secureTokenGenerator.hash(rawToken);
        if (emailVerificationTokenRepository.consume(tokenHash, now) == 0) {
            throw new InvalidOrExpiredVerificationTokenException(
                    "Verification token was not found, has already been used, or has expired");
        }

        EmailVerificationToken token = emailVerificationTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new IllegalStateException("Token hash " + tokenHash + " was consumed but not found "
                        + "immediately after within the same transaction - should be impossible"));

        User user = token.getUser();
        if (user.isEmailVerified()) {
            throw new InvalidOrExpiredVerificationTokenException("Account is already verified");
        }
        user.changePassword(passwordEncoder.encode(password));
        user.markEmailVerified();
        userRepository.save(user);

        return token;
    }

    public record IssuedVerificationToken(EmailVerificationToken token, String rawToken) {
    }
}
