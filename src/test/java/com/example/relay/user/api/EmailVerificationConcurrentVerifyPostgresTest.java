package com.example.relay.user.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.common.security.SecureTokenGenerator;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.application.EmailVerificationTokenService;
import com.example.relay.user.domain.EmailVerificationToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.InvalidOrExpiredVerificationTokenException;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest
class EmailVerificationConcurrentVerifyPostgresTest implements SharedPostgresContainer {

    @Autowired
    private EmailVerificationTokenService underTest;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SecureTokenGenerator secureTokenGenerator;

    private User user;

    @AfterEach
    void cleanUp() {
        if (user == null) {
            return;
        }
        emailVerificationTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .forEach(emailVerificationTokenRepository::delete);
        // Reload the user to get the current version before deleting (handles @Version field)
        user = userRepository.findById(user.getId()).orElse(user);
        userRepository.delete(user);
    }

    @Test
    void verify_underConcurrentAttemptsWithTheSameToken_succeedsExactlyOnce()
            throws InterruptedException, ExecutionException {
        String provisionalPassword = "provisionalPassword";
        user = userRepository.saveAndFlush(
                new User("concurrent-verify@example.com", passwordEncoder.encode(provisionalPassword)));
        EmailVerificationTokenService.IssuedVerificationToken issued = underTest.issue(user, Instant.now());

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        // Each racing thread submits a DIFFERENT password, so the final stored hash identifies
        // exactly which thread's consumeAndVerify transaction committed - direct coverage of
        // Design A's core property (password is bound to token consumption) under real
        // concurrency, not just token-consumption exclusivity.
        AtomicReference<String> winningPassword = new AtomicReference<>();

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            String password = "threadPassword-" + i;
            Callable<Void> attempt = () -> {
                ready.countDown();
                try {
                    go.await();
                    underTest.consumeAndVerify(issued.rawToken(), passwordEncoder.encode(password), Instant.now());
                    successes.incrementAndGet();
                    winningPassword.set(password);
                } catch (InvalidOrExpiredVerificationTokenException expected) {
                    // expected for the losing thread: EmailVerificationTokenService.consumeAndVerify
                    // translates its lockForUpdate's raw Hibernate OptimisticLockingFailureException into
                    // this same exception (see that method's javadoc), so this is the ONLY exception type a
                    // losing thread should ever see here now - anything else (e.g. the untranslated Hibernate
                    // exception escaping again) must fail the test loudly via Future#get below, not vanish
                    // into a broad catch the way it used to.
                }
                return null;
            };
            futures.add(executor.submit(attempt));
        }
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        for (Future<Void> future : futures) {
            future.get();
        }

        assertEquals(1, successes.get(), "exactly one concurrent verify attempt must succeed");

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertTrue(reloaded.isEmailVerified(),
                "the winning verify attempt must have committed email_verified=true to Postgres");
        assertTrue(passwordEncoder.matches(winningPassword.get(), reloaded.getPasswordHash()),
                "the committed password must be the one submitted by the thread that won the token race");
        String losingPassword = "threadPassword-0".equals(winningPassword.get()) ? "threadPassword-1"
                : "threadPassword-0";
        assertFalse(passwordEncoder.matches(losingPassword, reloaded.getPasswordHash()),
                "the losing thread's password must never become live");
        assertFalse(passwordEncoder.matches(provisionalPassword, reloaded.getPasswordHash()),
                "the provisional pre-verification password must have been replaced");
    }

    @Test
    void verify_rejectsADanglingTokenForAnAlreadyVerifiedUser_andNeverAppliesItsPassword() {
        String realPassword = "realOwnerPassword";
        user = userRepository.saveAndFlush(
                new User("dangling-token@example.com", passwordEncoder.encode("provisionalPassword")));
        EmailVerificationTokenService.IssuedVerificationToken firstToken = underTest.issue(user, Instant.now());

        underTest.consumeAndVerify(firstToken.rawToken(), passwordEncoder.encode(realPassword), Instant.now());

        // Simulate the documented race: a second token, still valid, exists for a user who is
        // already verified (e.g. issued by a concurrent resend just before the first consume
        // committed). Insert it directly rather than going through issue(), which would
        // invalidate the first (already-used) token anyway - the point is exercising the guard
        // against a still-valid dangling token, not reproducing the exact race timing.
        String danglingRawToken = "dangling-raw-token-" + user.getId();
        emailVerificationTokenRepository.saveAndFlush(new EmailVerificationToken(user,
                secureTokenGenerator.hash(danglingRawToken), Instant.now().plusSeconds(3600), Instant.now()));

        assertThrows(InvalidOrExpiredVerificationTokenException.class, () -> underTest.consumeAndVerify(
                danglingRawToken, passwordEncoder.encode("attackerChosenPassword"), Instant.now()));

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertTrue(passwordEncoder.matches(realPassword, reloaded.getPasswordHash()),
                "the real password from the first, legitimate consume must still be live");
        assertFalse(passwordEncoder.matches("attackerChosenPassword", reloaded.getPasswordHash()),
                "the dangling token's attempted password must never become live");
    }
}
