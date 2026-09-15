package com.example.relay.user.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.common.security.SecureTokenGenerator;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.application.EmailVerificationTokenService;
import com.example.relay.user.application.PasswordResetTokenService;
import com.example.relay.user.domain.EmailVerificationToken;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.InvalidOrExpiredResetTokenException;
import com.example.relay.user.exception.InvalidOrExpiredVerificationTokenException;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Covers Cases D1/D2/D3/E of docs/superpowers/specs/2026-09-15-user-activation-concurrency-design.md's test matrix,
 * under first-activation-wins semantics. D1/D2 include a duplicate live token of the WINNING type (an issue()-race
 * artifact - see the spec's "Interaction with the pre-existing issue() race" section) to prove activation
 * invalidates ALL PENDING-era tokens, not just the competing type. Cases A, F, G are covered by
 * AuthLifecycleIntegrationTest, EmailVerificationConcurrentVerifyPostgresTest, and
 * PasswordResetConcurrentConfirmPostgresTest respectively (the latter two updated in Tasks 3/4 for this plan's
 * signature change).
 */
@SpringBootTest
class UserActivationConcurrencyPostgresTest implements SharedPostgresContainer {

    @Autowired
    private EmailVerificationTokenService emailVerificationTokenService;

    @Autowired
    private PasswordResetTokenService passwordResetTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private SecureTokenGenerator secureTokenGenerator;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User user;

    @AfterEach
    void cleanUp() {
        if (user == null) {
            return;
        }
        emailVerificationTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .forEach(emailVerificationTokenRepository::delete);
        passwordResetTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .forEach(passwordResetTokenRepository::delete);
        // deleteById (not delete(user)): the test bodies above race concurrent activateIfPending/setPasswordOnly
        // calls that bump User#version underneath this stale in-memory `user` field. delete(entity) merges the
        // detached entity first and fails its own optimistic-lock check against that stale version; deleteById
        // issues a plain delete-by-PK and sidesteps the version check entirely.
        userRepository.deleteById(user.getId());
    }

    /**
     * Case D1 (satisfies the reviewed design's "D4"): verify wins first (deterministic order). Seeds a SECOND live
     * verification token (V2) in addition to the one actually consumed (V1), simulating a duplicate left over from
     * issue()'s own pre-existing, separately-accepted non-atomicity race - see the design spec's "Interaction with
     * the pre-existing issue() race" section. Both V2 and the competing reset token (R1) must be rejected afterward;
     * neither can subsequently touch the password V1 established.
     */
    @Test
    void verifyActivatesFirst_thenTheDuplicateVerificationTokenAndTheCompetingResetTokenAreBothRejected() {
        user = userRepository.saveAndFlush(new User("d1-verify-first@example.com", passwordEncoder.encode("provisional")));
        EmailVerificationTokenService.IssuedVerificationToken v1 = emailVerificationTokenService.issue(user, Instant.now());
        String v2RawToken = "duplicate-verify-token-" + user.getId();
        emailVerificationTokenRepository.saveAndFlush(new EmailVerificationToken(user,
                secureTokenGenerator.hash(v2RawToken), Instant.now().plusSeconds(3600), Instant.now()));
        String r1RawToken = secureTokenGenerator.generateRawToken();
        passwordResetTokenRepository.saveAndFlush(new PasswordResetToken(user,
                secureTokenGenerator.hash(r1RawToken), Instant.now().plusSeconds(1800), Instant.now()));

        emailVerificationTokenService.consumeAndVerify(v1.rawToken(), passwordEncoder.encode("viaV1"), Instant.now());

        assertThrows(InvalidOrExpiredVerificationTokenException.class, () -> emailVerificationTokenService
                .consumeAndVerify(v2RawToken, passwordEncoder.encode("viaV2"), Instant.now()));
        assertThrows(InvalidOrExpiredResetTokenException.class, () -> passwordResetTokenService
                .consumeAndResetPassword(r1RawToken, passwordEncoder.encode("viaR1"), Instant.now()));

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertTrue(reloaded.isEmailVerified());
        assertTrue(passwordEncoder.matches("viaV1", reloaded.getPasswordHash()));
        assertTrue(!passwordEncoder.matches("viaV2", reloaded.getPasswordHash()));
        assertTrue(!passwordEncoder.matches("viaR1", reloaded.getPasswordHash()));
    }

    /**
     * Case D2 (satisfies the reviewed design's "D5"): reset wins first (deterministic order). Seeds a SECOND live
     * reset token (R2) in addition to the one actually consumed (R1), simulating the same class of issue()-race
     * duplicate as D1's V2. Both R2 and the competing verification token (V1) must be rejected afterward.
     */
    @Test
    void resetActivatesFirst_thenTheDuplicateResetTokenAndTheCompetingVerificationTokenAreBothRejected() {
        user = userRepository.saveAndFlush(new User("d2-reset-first@example.com", passwordEncoder.encode("provisional")));
        EmailVerificationTokenService.IssuedVerificationToken v1 = emailVerificationTokenService.issue(user, Instant.now());
        String r1RawToken = secureTokenGenerator.generateRawToken();
        passwordResetTokenRepository.saveAndFlush(new PasswordResetToken(user,
                secureTokenGenerator.hash(r1RawToken), Instant.now().plusSeconds(1800), Instant.now()));
        String r2RawToken = "duplicate-reset-token-" + user.getId();
        passwordResetTokenRepository.saveAndFlush(new PasswordResetToken(user,
                secureTokenGenerator.hash(r2RawToken), Instant.now().plusSeconds(1800), Instant.now()));

        passwordResetTokenService.consumeAndResetPassword(r1RawToken, passwordEncoder.encode("viaR1"), Instant.now());

        assertThrows(InvalidOrExpiredResetTokenException.class, () -> passwordResetTokenService
                .consumeAndResetPassword(r2RawToken, passwordEncoder.encode("viaR2"), Instant.now()));
        assertThrows(InvalidOrExpiredVerificationTokenException.class, () -> emailVerificationTokenService
                .consumeAndVerify(v1.rawToken(), passwordEncoder.encode("viaV1"), Instant.now()));

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertTrue(reloaded.isEmailVerified());
        assertTrue(passwordEncoder.matches("viaR1", reloaded.getPasswordHash()));
        assertTrue(!passwordEncoder.matches("viaR2", reloaded.getPasswordHash()));
        assertTrue(!passwordEncoder.matches("viaV1", reloaded.getPasswordHash()));
    }

    /**
     * Case D3: verify and reset genuinely race on the same PENDING user. Under first-activation-wins semantics,
     * exactly one must succeed - the loser's token is invalidated by the winner before the loser's own consume runs,
     * so the loser always throws. This also exercises the lock-ordering fix directly: without it, this test
     * deadlocks/times out instead of completing.
     */
    @Test
    void concurrentVerifyAndReset_onPendingUser_exactlyOneWins_theOtherIsRejected() throws InterruptedException {
        user = userRepository.saveAndFlush(new User("d3-race@example.com", passwordEncoder.encode("provisional")));
        EmailVerificationTokenService.IssuedVerificationToken verifyToken =
                emailVerificationTokenService.issue(user, Instant.now());
        String resetRawToken = secureTokenGenerator.generateRawToken();
        passwordResetTokenRepository.saveAndFlush(new PasswordResetToken(user,
                secureTokenGenerator.hash(resetRawToken), Instant.now().plusSeconds(1800), Instant.now()));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();

        executor.submit(() -> {
            ready.countDown();
            try {
                go.await();
                emailVerificationTokenService.consumeAndVerify(verifyToken.rawToken(), passwordEncoder.encode("viaVerify"),
                        Instant.now());
                successes.incrementAndGet();
            } catch (InvalidOrExpiredVerificationTokenException e) {
                rejections.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        executor.submit(() -> {
            ready.countDown();
            try {
                go.await();
                passwordResetTokenService.consumeAndResetPassword(resetRawToken, passwordEncoder.encode("viaReset"),
                        Instant.now());
                successes.incrementAndGet();
            } catch (InvalidOrExpiredResetTokenException e) {
                rejections.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                "both attempts must finish within the timeout - a hang here means the lock ordering deadlocks");

        assertEquals(1, successes.get(), "exactly one of verify/reset must win the activation race");
        assertEquals(1, rejections.get(), "the loser's token must be rejected, never silently ignored");
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertTrue(reloaded.isEmailVerified(), "the account must end ACTIVE regardless of which side won");
        assertTrue(passwordEncoder.matches("viaVerify", reloaded.getPasswordHash())
                ^ passwordEncoder.matches("viaReset", reloaded.getPasswordHash()),
                "the final password must be the winner's alone, never both, never neither");
    }

    /**
     * Case E: two DISTINCT valid reset tokens for an ALREADY-ACTIVE user (reachable via a pre-existing,
     * already-documented race in issue() itself - see the design spec point 7, not reproduced here; direct insertion
     * is used to reach the same state deterministically). Both are ordinary password changes, not activations, so
     * neither invalidates the other; the final password is whichever commits last.
     *
     * <p>
     * This is DELIBERATELY different from D1/D2/D3 above: those are a PENDING account's mutually-exclusive
     * activation paths, where the first winner terminates the other tokens' ability to mutate the account at all.
     * This is an ALREADY-ACTIVE account's ordinary password-reset flow, where multiple independently-valid reset
     * credentials are each allowed to change the password - see the design spec's "PENDING-race vs. ACTIVE-reset
     * semantics" section. Do not read this test as evidence that D1-D3's invalidation logic is optional; it exists
     * only because no activation transition happens here (activateIfPending returns 0 for an already-ACTIVE row, so
     * setPasswordOnly is used instead and never invalidates anything).
     */
    @Test
    void concurrentResetsWithTwoDistinctValidTokens_onActiveUser_bothSucceed_noStaleFieldCorruption() throws InterruptedException {
        user = userRepository.saveAndFlush(new User("e-two-resets@example.com", passwordEncoder.encode("original")));
        user.markEmailVerified();
        user = userRepository.saveAndFlush(user);
        String rawTokenA = secureTokenGenerator.generateRawToken();
        String rawTokenB = secureTokenGenerator.generateRawToken();
        passwordResetTokenRepository.saveAndFlush(
                new PasswordResetToken(user, secureTokenGenerator.hash(rawTokenA), Instant.now().plusSeconds(1800), Instant.now()));
        passwordResetTokenRepository.saveAndFlush(
                new PasswordResetToken(user, secureTokenGenerator.hash(rawTokenB), Instant.now().plusSeconds(1800), Instant.now()));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();

        executor.submit(() -> {
            ready.countDown();
            try {
                go.await();
                passwordResetTokenService.consumeAndResetPassword(rawTokenA, passwordEncoder.encode("passwordA"), Instant.now());
                successes.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ignored) {
                // not expected here - tokens are distinct - but tolerated defensively
            }
        });
        executor.submit(() -> {
            ready.countDown();
            try {
                go.await();
                passwordResetTokenService.consumeAndResetPassword(rawTokenB, passwordEncoder.encode("passwordB"), Instant.now());
                successes.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ignored) {
                // not expected here - tokens are distinct - but tolerated defensively
            }
        });

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "both attempts must finish within the timeout");

        assertEquals(2, successes.get(), "both distinct-token resets must succeed independently");
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertTrue(reloaded.isEmailVerified(), "email_verified must never be observed false after either commit");
        assertTrue(passwordEncoder.matches("passwordA", reloaded.getPasswordHash())
                || passwordEncoder.matches("passwordB", reloaded.getPasswordHash()),
                "the final password must be one of the two legitimately-submitted ones, never the stale original");
    }
}
