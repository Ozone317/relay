package com.example.relay.user.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.application.EmailVerificationService;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class EmailVerificationConcurrentResendPostgresTest implements SharedPostgresContainer {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired
    private EmailVerificationService underTest;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String email;

    @AfterEach
    void cleanUp() {
        if (email == null) {
            return;
        }
        redisTemplate.delete("email-verification:cooldown:" + email);
        userRepository.findByEmail(email).ifPresent(user -> {
            emailVerificationTokenRepository.findAll().stream()
                    .filter(token -> token.getUser().getId().equals(user.getId()))
                    .forEach(emailVerificationTokenRepository::delete);
            userRepository.delete(user);
        });
    }

    @Test
    void resend_underConcurrentRequestsForTheSameEmail_onlyOneWinsTheCooldown() throws InterruptedException {
        email = "concurrent-resend@example.com";
        userRepository.saveAndFlush(new User(email, "hash"));
        redisTemplate.delete("email-verification:cooldown:" + email);

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException ignored) {
                }
                underTest.resend(email, "127.0.0.1");
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // The real assertion of interest: exactly one token row was issued for this user, proving
        // only one of the two concurrent resend() calls actually got past the cooldown and issued a
        // token - not just that the cooldown key exists (which is true regardless of how many
        // threads won).
        User user = userRepository.findByEmail(email).orElseThrow();
        long tokenCount = emailVerificationTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId())).count();
        assertEquals(1, tokenCount, "exactly one concurrent resend() call must issue a verification token");
    }
}
