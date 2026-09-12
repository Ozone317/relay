package com.example.relay.common.ratelimit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.support.SharedPostgresContainer;
import org.junit.jupiter.api.BeforeEach;
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
class PasswordResetRateLimiterTest implements SharedPostgresContainer {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired
    private PasswordResetRateLimiter underTest;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void allow_true_onFirstRequest() {
        assertTrue(underTest.allow("cooldown-a@example.com", "10.0.0.1"));
    }

    @Test
    void allow_false_onASecondRequestWithinTheCooldown() {
        underTest.allow("cooldown-b@example.com", "10.0.0.2");

        assertFalse(underTest.allow("cooldown-b@example.com", "10.0.0.2"));
    }

    @Test
    void allow_false_onceEmailHourlyCapExceeded() {
        for (int i = 0; i < 5; i++) {
            // A fresh IP each time so only the email-hourly cap can be the thing that trips.
            underTest.allow("hourly-cap@example.com", "10.0.1." + i);
        }

        assertFalse(underTest.allow("hourly-cap@example.com", "10.0.1.99"));
    }

    @Test
    void allow_false_onceIpHourlyCapExceeded_acrossDifferentEmails() {
        for (int i = 0; i < 20; i++) {
            underTest.allow("ip-cap-" + i + "@example.com", "10.0.2.1");
        }

        assertFalse(underTest.allow("ip-cap-final@example.com", "10.0.2.1"));
    }

    @Test
    void hourlyCapScript_doesNotExtendTheWindowTtlOnRepeatedIncrements() {
        String key = "password-reset:hourly:email:ttl-check@example.com";

        underTest.allow("ttl-check@example.com", "10.0.3.1");
        Long firstTtl = redisTemplate.getExpire(key);
        underTest.allow("ttl-check@example.com", "10.0.3.2");
        Long secondTtl = redisTemplate.getExpire(key);

        assertTrue(firstTtl != null && firstTtl > 0);
        assertTrue(secondTtl != null && secondTtl <= firstTtl,
                "a later increment must not push the TTL back up - the window would never close");
    }
}
