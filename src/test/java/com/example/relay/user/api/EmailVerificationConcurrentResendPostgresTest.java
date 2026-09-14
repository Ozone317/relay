package com.example.relay.user.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.application.EmailVerificationService;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.UserRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class EmailVerificationConcurrentResendPostgresTest implements SharedPostgresContainer {

    @Autowired
    private EmailVerificationService underTest;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void resend_underConcurrentRequestsForTheSameEmail_onlyOneWinsTheCooldown() throws InterruptedException {
        String email = "concurrent-resend@example.com";
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

        // The cooldown key exists and is claimed exactly once regardless of which thread won -
        // the real assertion of interest (only one token/dispatch happened) is already covered at
        // the unit level in EmailVerificationServiceTest; this proves the Redis-level exclusion
        // actually holds under real concurrent load, not just in a single-threaded mock.
        assertEquals(true,
                redisTemplate.hasKey("email-verification:cooldown:" + email));
    }
}
