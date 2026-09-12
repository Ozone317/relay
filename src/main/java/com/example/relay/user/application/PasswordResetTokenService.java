package com.example.relay.user.application;

import com.example.relay.common.security.SecureTokenGenerator;
import com.example.relay.user.PasswordResetProperties;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.InvalidOrExpiredResetTokenException;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the two transactional DB operations for password reset, kept on its own bean (like RefreshTokenService relative
 * to AuthService) so PasswordResetService can call a genuinely committed transaction and only then do its post-commit
 * email dispatch - a method calling another @Transactional method on itself would bypass Spring's AOP proxy and
 * silently run with no transaction at all.
 */
@Service
public class PasswordResetTokenService {

    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final UserRepository userRepository;
    private final SecureTokenGenerator secureTokenGenerator;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetProperties passwordResetProperties;

    public PasswordResetTokenService(PasswordResetTokenRepository passwordResetTokenRepository,
            UserRepository userRepository, SecureTokenGenerator secureTokenGenerator,
            RefreshTokenService refreshTokenService, PasswordEncoder passwordEncoder,
            PasswordResetProperties passwordResetProperties) {
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.userRepository = userRepository;
        this.secureTokenGenerator = secureTokenGenerator;
        this.refreshTokenService = refreshTokenService;
        this.passwordEncoder = passwordEncoder;
        this.passwordResetProperties = passwordResetProperties;
    }

    /**
     * Invalidates every previously-unused token for this user, then issues a new one - both in one transaction,
     * mirroring AttemptService.markFailedAndCreateRetry's "supersede, don't leave orphaned state" shape. The
     * invalidation is also what removes a stale, never-dispatched row from
     * com.example.relay.user.recovery.PasswordResetEmailRecoverySweeper's future candidate scans, as an intrinsic side
     * effect, not a separate mechanism.
     */
    @Transactional
    public IssuedResetToken issue(User user, Instant now) {
        passwordResetTokenRepository.invalidateAllForUser(user.getId(), now);
        String rawToken = secureTokenGenerator.generateRawToken();
        PasswordResetToken token = passwordResetTokenRepository.saveAndFlush(new PasswordResetToken(user,
                secureTokenGenerator.hash(rawToken), now.plus(passwordResetProperties.getTokenTtl()), now));
        return new IssuedResetToken(token, rawToken);
    }

    /**
     * The atomic consume UPDATE's row count is the sole authority on token validity - not any prior SELECT. A
     * successful consume, the password update, and revoking every refresh-token session all happen in this one
     * transaction: if any later step throws, the consume itself rolls back too, so a failed password update never
     * silently burns the token.
     */
    @Transactional
    public PasswordResetToken consumeAndResetPassword(String rawToken, String newPassword, Instant now) {
        String tokenHash = secureTokenGenerator.hash(rawToken);
        if (passwordResetTokenRepository.consume(tokenHash, now) == 0) {
            throw new InvalidOrExpiredResetTokenException(
                    "Reset token was not found, has already been used, or has expired");
        }

        PasswordResetToken token = passwordResetTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new IllegalStateException("Token hash " + tokenHash + " was consumed but not found "
                        + "immediately after within the same transaction - should be impossible"));

        User user = token.getUser();
        user.changePassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        refreshTokenService.revokeAll(user.getId(), now);

        return token;
    }

    public record IssuedResetToken(PasswordResetToken token, String rawToken) {
    }
}
