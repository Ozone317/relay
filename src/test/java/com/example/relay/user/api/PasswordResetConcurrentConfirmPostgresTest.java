package com.example.relay.user.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.common.security.SecureTokenGenerator;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.endpoint.infrastructure.EndpointRepository;
import com.example.relay.environment.infrastructure.EnvironmentRepository;
import com.example.relay.event.infrastructure.EventRepository;
import com.example.relay.message.infrastructure.MessageRepository;
import com.example.relay.subscription.infrastructure.SubscriptionRepository;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.application.PasswordResetTokenService;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.InvalidOrExpiredResetTokenException;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest
class PasswordResetConcurrentConfirmPostgresTest implements SharedPostgresContainer {

    @Autowired
    private PasswordResetTokenService underTest;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private SecureTokenGenerator secureTokenGenerator;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    // Every other table that can hold a row transitively referencing users(id), cleaned up here in
    // FK-safe (children-first) order before userRepository.deleteAll() below - not because this
    // test's own scenario touches any of them, but because this shared Postgres container is reused
    // across every @SpringBootTest class in the suite (see SharedPostgresContainer's javadoc), and a
    // blanket "delete every user" only succeeds once nothing anywhere still references one.
    @Autowired
    private AttemptRepository attemptRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private AppRepository appRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    private User user;
    private String rawToken;

    @BeforeEach
    void setUp() {
        attemptRepository.deleteAll();
        deliveryRepository.deleteAll();
        messageRepository.deleteAll();
        subscriptionRepository.deleteAll();
        endpointRepository.deleteAll();
        eventRepository.deleteAll();
        appRepository.deleteAll();
        environmentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();

        user = userRepository.save(new User("concurrent-confirm@example.com", "original-hash"));
        rawToken = secureTokenGenerator.generateRawToken();
        Instant now = Instant.now();
        passwordResetTokenRepository
                .save(new PasswordResetToken(user, secureTokenGenerator.hash(rawToken), now.plusSeconds(1800), now));
    }

    @Test
    void exactlyOneOfTwoConcurrentConfirms_forTheSameToken_succeeds() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        Runnable attempt = () -> {
            readyLatch.countDown();
            try {
                startLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                underTest.consumeAndResetPassword(rawToken, "newPassword123", Instant.now());
                successCount.incrementAndGet();
            } catch (InvalidOrExpiredResetTokenException e) {
                rejectedCount.incrementAndGet();
            }
        };

        executor.submit(attempt);
        executor.submit(attempt);
        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);

        assertEquals(true, finished, "both attempts must finish within the timeout");
        assertEquals(1, successCount.get());
        assertEquals(1, rejectedCount.get());

        PasswordResetToken reloaded =
                passwordResetTokenRepository.findByTokenHash(secureTokenGenerator.hash(rawToken)).orElseThrow();
        assertNotNull(reloaded.getUsedAt());
    }
}
