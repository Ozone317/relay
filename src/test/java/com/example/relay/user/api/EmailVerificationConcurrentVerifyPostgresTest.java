package com.example.relay.user.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.application.EmailVerificationTokenService;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private User user;

    @AfterEach
    void cleanUp() {
        if (user == null) {
            return;
        }
        emailVerificationTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .forEach(emailVerificationTokenRepository::delete);
        userRepository.delete(user);
    }

    @Test
    void verify_underConcurrentAttemptsWithTheSameToken_succeedsExactlyOnce() throws InterruptedException {
        user = userRepository.saveAndFlush(new User("concurrent-verify@example.com", "provisional-hash"));
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

        for (int i = 0; i < threadCount; i++) {
            String password = "threadPassword-" + i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    underTest.consumeAndVerify(issued.rawToken(), password, Instant.now());
                    successes.incrementAndGet();
                    winningPassword.set(password);
                } catch (Exception ignored) {
                    // expected for the losing thread
                } finally {
                    // nothing else to release
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

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
        assertFalse(passwordEncoder.matches("provisional-hash", reloaded.getPasswordHash()),
                "the provisional pre-verification hash must have been replaced");
    }
}
