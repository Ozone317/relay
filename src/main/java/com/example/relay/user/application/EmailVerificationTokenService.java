package com.example.relay.user.application;

import com.example.relay.common.security.SecureTokenGenerator;
import com.example.relay.user.EmailVerificationProperties;
import com.example.relay.user.domain.EmailVerificationToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.InvalidOrExpiredVerificationTokenException;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the two transactional DB operations for email verification, mirroring PasswordResetTokenService's relationship
 * to PasswordResetService exactly.
 */
@Service
public class EmailVerificationTokenService {

    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final UserRepository userRepository;
    private final SecureTokenGenerator secureTokenGenerator;
    private final EmailVerificationProperties emailVerificationProperties;
    private final EntityManager entityManager;

    public EmailVerificationTokenService(EmailVerificationTokenRepository emailVerificationTokenRepository,
            PasswordResetTokenRepository passwordResetTokenRepository, UserRepository userRepository,
            SecureTokenGenerator secureTokenGenerator, EmailVerificationProperties emailVerificationProperties,
            EntityManager entityManager) {
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.userRepository = userRepository;
        this.secureTokenGenerator = secureTokenGenerator;
        this.emailVerificationProperties = emailVerificationProperties;
        this.entityManager = entityManager;
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
     * Consumes the one currently-valid verification token AND sets the account's real password, atomically, under
     * first-activation-wins semantics - see docs/superpowers/specs/2026-09-15-user-activation-concurrency-design.md.
     *
     * <p>
     * {@code passwordHash} arrives here ALREADY hashed - see
     * {@link EmailVerificationService#verify(String, String)}, which hashes before calling this method so bcrypt's
     * ~100ms never runs inside this transaction, and never while {@link UserRepository#lockForUpdate} below is held.
     *
     * <p>
     * Lock order is load-bearing: {@code lockForUpdate} is acquired BEFORE this token is consumed, and
     * PasswordResetTokenService.consumeAndResetPassword acquires the SAME user row lock before consuming ITS token,
     * in the same position in its own sequence. Without that shared ordering, a verify-vs-reset race on the same
     * PENDING user can deadlock: each side would otherwise hold its own token's lock while waiting on the other's
     * users-row lock. See the design spec's "Lock ordering" section for the full trace. The user lookup before the
     * lock is a plain, unlocked read used only to learn which row to lock - it proves nothing about token validity,
     * which is established afterward, under the lock, by consume()'s and activateIfPending's own atomic row counts.
     *
     * <p>
     * The {@code entityManager.detach} call below is load-bearing, not tidy-up. {@code EmailVerificationToken#user}
     * is a default-EAGER {@code @ManyToOne}, so {@code findByTokenHash} alone already JOIN-loads a MANAGED,
     * version-stamped User into this transaction's persistence context, unlocked, before any application code runs.
     * Left managed, {@link UserRepository#lockForUpdate} below is not a fresh load but a lock-mode UPGRADE on that
     * already-managed instance, and Hibernate re-validates its cached {@code version} against the row it just
     * locked - which, because the lock made this transaction queue behind whoever held the row, has essentially
     * always moved by the time it unblocks, so the upgrade throws ObjectOptimisticLockingFailureException. That
     * fires indistinguishably for a genuine lost activation race AND for a wholly unrelated concurrent write, so
     * translating it to "invalid or expired token" wrongly rejected valid tokens. Detaching removes the stale cached
     * instance, making lockForUpdate a genuine {@code SELECT ... FOR UPDATE} with nothing to version-check; validity
     * is then decided only by consume()'s and activateIfPending's own atomic row counts, under the lock. See
     * PasswordResetTokenService.consumeAndResetPassword's identical note - this is symmetric across both flows.
     *
     * <p>
     * The earlier {@code catch (OptimisticLockingFailureException)} around lockForUpdate (commit 2fff4da) is
     * deliberately GONE, not merely unused - see PasswordResetTokenService.consumeAndResetPassword's javadoc for the
     * full reasoning and the empirical evidence that it is unreachable after the detach, plus the caveat about the
     * injected EntityManager being request-scoped rather than transaction-scoped under this project's default
     * {@code spring.jpa.open-in-view}.
     *
     * <p>
     * If this call performs the PENDING -> ACTIVE transition, EVERY live PENDING-era token for this user, of BOTH
     * types, is invalidated in the same transaction - not just the other type. invalidateAllForUser is called on
     * both EmailVerificationTokenRepository (covering a duplicate live verification token left over from issue()'s
     * own pre-existing, separately-accepted non-atomicity) AND PasswordResetTokenRepository (covering a competing
     * reset token). Each call is a blanket WHERE used_at IS NULL update, so it is a no-op against the token this
     * method just consumed and only touches genuinely-still-live siblings. If the account was already ACTIVE (the
     * pre-existing "dangling token" case), activateIfPending returns 0 and this method rejects without invalidating
     * anything else - see docs/superpowers/specs/2026-09-15-user-activation-concurrency-design.md.
     */
    @Transactional
    public EmailVerificationToken consumeAndVerify(String rawToken, String passwordHash, Instant now) {
        String tokenHash = secureTokenGenerator.hash(rawToken);
        EmailVerificationToken token = emailVerificationTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidOrExpiredVerificationTokenException(
                        "Verification token was not found, has already been used, or has expired"));
        UUID userId = token.getUser().getId();
        entityManager.detach(token.getUser());

        userRepository.lockForUpdate(userId);

        if (emailVerificationTokenRepository.consume(tokenHash, now) == 0) {
            throw new InvalidOrExpiredVerificationTokenException(
                    "Verification token was not found, has already been used, or has expired");
        }

        if (userRepository.activateIfPending(userId, passwordHash) == 0) {
            throw new InvalidOrExpiredVerificationTokenException("Account is already verified");
        }

        emailVerificationTokenRepository.invalidateAllForUser(userId, now);
        passwordResetTokenRepository.invalidateAllForUser(userId, now);

        return token;
    }

    public record IssuedVerificationToken(EmailVerificationToken token, String rawToken) {
    }
}
