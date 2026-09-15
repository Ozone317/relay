package com.example.relay.user.application;

import com.example.relay.common.security.SecureTokenGenerator;
import com.example.relay.user.PasswordResetProperties;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.InvalidOrExpiredResetTokenException;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import jakarta.persistence.EntityManager;
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
    private final EntityManager entityManager;

    public PasswordResetTokenService(PasswordResetTokenRepository passwordResetTokenRepository,
            EmailVerificationTokenRepository emailVerificationTokenRepository, UserRepository userRepository,
            SecureTokenGenerator secureTokenGenerator, RefreshTokenService refreshTokenService,
            PasswordResetProperties passwordResetProperties, EntityManager entityManager) {
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.userRepository = userRepository;
        this.secureTokenGenerator = secureTokenGenerator;
        this.refreshTokenService = refreshTokenService;
        this.passwordResetProperties = passwordResetProperties;
        this.entityManager = entityManager;
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
     * The {@code entityManager.detach} call below is load-bearing, not tidy-up. {@code PasswordResetToken#user} is a
     * default-EAGER {@code @ManyToOne}, so {@code findByTokenHash} alone already JOIN-loads a MANAGED, version-stamped
     * User into this transaction's persistence context, unlocked, before any application code runs. Left managed, the
     * subsequent {@link UserRepository#lockForUpdate} is not a fresh load but a lock-mode UPGRADE on that already-
     * managed instance, and Hibernate re-validates its cached {@code version} against the row it just locked. Because
     * the lock made this transaction QUEUE behind whoever held the row, by the time it unblocks the version has
     * essentially always moved - so the upgrade throws ObjectOptimisticLockingFailureException. That fires for BOTH a
     * genuine lost activation race AND a wholly unrelated concurrent password change (two distinct valid reset tokens
     * on an already-ACTIVE account, Case E of the design spec's matrix), and nothing at that call site can tell the
     * two apart - so translating it to "invalid or expired token" wrongly rejected a perfectly valid token. Detaching
     * first removes the stale cached instance entirely, making lockForUpdate a genuine {@code SELECT ... FOR UPDATE}
     * with nothing to version-check. Validity is then decided ONLY where it always should have been: by consume()'s
     * and activateIfPending's own atomic affected-row counts, under the lock. A loser of a real activation race still
     * rejects cleanly, because the winner's invalidateAllForUser already set its {@code used_at} and consume() returns
     * 0. Only the id is read before detaching; the entity's other fields are deliberately never used.
     *
     * <p>
     * The earlier {@code catch (OptimisticLockingFailureException)} around lockForUpdate (commit 2fff4da) is
     * deliberately GONE, not merely unused: detaching removes the only construct in this method that could raise it,
     * verified by instrumenting the call site and observing zero occurrences across repeated runs of every
     * verify/reset concurrency suite. Keeping it as "defense in depth" would be actively harmful, not free - it can
     * only ever misclassify: the exception cannot distinguish a lost race from an unrelated concurrent write, so
     * reinstating it would re-open exactly the Case E bug it is being removed for, while masking any genuine future
     * optimistic-lock bug as a routine 400 instead of failing loudly. If a future edit reintroduces a managed User
     * before this line, the right outcome is the raw exception surfacing, not a silent false rejection.
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
        UUID userId = token.getUser().getId();
        entityManager.detach(token.getUser());

        userRepository.lockForUpdate(userId);

        if (passwordResetTokenRepository.consume(tokenHash, now) == 0) {
            throw new InvalidOrExpiredResetTokenException(
                    "Reset token was not found, has already been used, or has expired");
        }

        if (userRepository.activateIfPending(userId, passwordHash) == 1) {
            emailVerificationTokenRepository.invalidateAllForUser(userId, now);
            passwordResetTokenRepository.invalidateAllForUser(userId, now);
        } else {
            userRepository.setPasswordOnly(userId, passwordHash);
        }

        refreshTokenService.revokeAll(userId, now);

        return token;
    }

    public record IssuedResetToken(PasswordResetToken token, String rawToken) {
    }
}
