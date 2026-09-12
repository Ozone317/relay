package com.example.relay.common.ratelimit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class PasswordResetRateLimiterRedisDownTest {

    @Test
    void allow_returnsTrue_whenRedisThrows() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(java.time.Duration.class)))
                .thenThrow(new QueryTimeoutException("simulated Redis outage"));

        PasswordResetRateLimiterProperties properties = new PasswordResetRateLimiterProperties();
        PasswordResetRateLimiter underTest = new PasswordResetRateLimiter(redisTemplate, properties);

        assertTrue(underTest.allow("redis-down@example.com", "10.0.0.1"),
                "a Redis failure must fail open, never block password recovery");
    }
}
