package com.example.relay.user.application;

import com.example.relay.common.security.SecureTokenGenerator;
import com.example.relay.user.PasswordResetProperties;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.InvalidOrExpiredResetTokenException;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Instant;
import java.util.UUID;
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
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final UserRepository userRepository;
    private final SecureTokenGenerator secureTokenGenerator;
    private final RefreshTokenService refreshTokenService;
    private final PasswordResetProperties passwordResetProperties;

    public PasswordResetTokenService(PasswordResetTokenRepository passwordResetTokenRepository,
            EmailVerificationTokenRepository emailVerificationTokenRepository, UserRepository userRepository,
            SecureTokenGenerator secureTokenGenerator, RefreshTokenService refreshTokenService,
            PasswordResetProperties passwordResetProperties) {
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.userRepository = userRepository;
        this.secureTokenGenerator = secureTokenGenerator;
        this.refreshTokenService = refreshTokenService;
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
        return issue(user, now, now);
    }

    /**
     * Used only by com.example.relay.user.recovery.PasswordResetEmailRecoverySweeper: reissues a token exactly like
     * {@link #issue(User, Instant)}, except {@code firstRequestedAt} is carried forward from the row being superseded
     * rather than reset to {@code now} - this is what lets
     * com.example.relay.user.recovery.PasswordResetEmailRecoveryProperties#maxRecoveryWindow bound an entire recovery
     * chain instead of restarting on every reissue.
     */
    @Transactional
    public IssuedResetToken reissueForRecovery(User user, Instant now, Instant firstRequestedAt) {
        return issue(user, now, firstRequestedAt);
    }

    /**
     * Used only by com.example.relay.user.recovery.PasswordResetEmailRecoverySweeper's give-up path - a thin
     *
     * @Transactional wrapper, the same shape as deliveryengine's AttemptService#claimDeadLetterNotification, since
     *                PasswordResetTokenRepository#giveUpOn is a custom @Modifying @Query method and Spring Data JPA
     *                does not wrap such methods in a transaction on its own (unlike SimpleJpaRepository's built-in CRUD
     *                methods) - calling it directly from the sweeper's un-transactional sweep() throws
     *                TransactionRequiredException.
     */
    @Transactional
    public boolean giveUpOnRecovery(UUID tokenId, Instant now) {
        return passwordResetTokenRepository.giveUpOn(tokenId, now) == 1;
    }

    private IssuedResetToken issue(User user, Instant now, Instant firstRequestedAt) {
        passwordResetTokenRepository.invalidateAllForUser(user.getId(), now);
        String rawToken = secureTokenGenerator.generateRawToken();
        PasswordResetToken token = passwordResetTokenRepository
                .saveAndFlush(new PasswordResetToken(user, secureTokenGenerator.hash(rawToken),
                        now.plus(passwordResetProperties.getTokenTtl()), now, firstRequestedAt));
        return new IssuedResetToken(token, rawToken);
    }

    /**
     * Consumes the token and atomically applies its password, under first-activation-wins semantics - see
     * docs/superpowers/specs/2026-09-15-user-activation-concurrency-design.md. {@code passwordHash} arrives here
     * ALREADY hashed - see {@link PasswordResetService#confirmReset(String, String)}, which hashes before calling
     * this method so bcrypt never runs inside this transaction or while any row lock is held.
     *
     * <p>
     * Lock order is load-bearing and MUST match EmailVerificationTokenService.consumeAndVerify's: lock the user row
     * before consuming this token, not after - see that method's javadoc for the deadlock this prevents.
     *
     * <p>
     * If {@link UserRepository#activateIfPending} succeeds, this reset just performed the PENDING -> ACTIVE
     * transition (reset-as-activation): EVERY live PENDING-era token for this user, of BOTH types, is invalidated in
     * the same transaction - not just the other type. invalidateAllForUser is called on both
     * EmailVerificationTokenRepository (covering a competing verification token) AND PasswordResetTokenRepository
     * (covering a duplicate live reset token left over from issue()'s own pre-existing, separately-accepted
     * non-atomicity). Each call is a blanket WHERE used_at IS NULL update, so it is a no-op against the token this
     * method just consumed. If the account was already ACTIVE, this is an ordinary password change:
     * {@link UserRepository#setPasswordOnly} is used instead, which never touches email_verified and never
     * invalidates any other token - see
     * docs/superpowers/specs/2026-09-15-user-activation-concurrency-design.md's "PENDING-race vs. ACTIVE-reset
     * semantics" section for why these two branches deliberately behave differently.
     */
    @Transactional
    public PasswordResetToken consumeAndResetPassword(String rawToken, String passwordHash, Instant now) {
        String tokenHash = secureTokenGenerator.hash(rawToken);
        PasswordResetToken token = passwordResetTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidOrExpiredResetTokenException(
                        "Reset token was not found, has already been used, or has expired"));
        User user = token.getUser();

        userRepository.lockForUpdate(user.getId());

        if (passwordResetTokenRepository.consume(tokenHash, now) == 0) {
            throw new InvalidOrExpiredResetTokenException(
                    "Reset token was not found, has already been used, or has expired");
        }

        if (userRepository.activateIfPending(user.getId(), passwordHash) == 1) {
            emailVerificationTokenRepository.invalidateAllForUser(user.getId(), now);
            passwordResetTokenRepository.invalidateAllForUser(user.getId(), now);
        } else {
            userRepository.setPasswordOnly(user.getId(), passwordHash);
        }

        refreshTokenService.revokeAll(user.getId(), now);

        return token;
    }

    public record IssuedResetToken(PasswordResetToken token, String rawToken) {
    }
}
