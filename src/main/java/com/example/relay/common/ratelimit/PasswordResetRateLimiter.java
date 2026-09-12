package com.example.relay.common.ratelimit;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Owns every Redis mechanic for password-reset rate limiting - key naming, TTLs, the atomic hourly-cap script, limit
 * configuration. Callers only ever see {@link #allow(String, String)}.
 *
 * <p>
 * Checks run cooldown, then email-hourly, then IP-hourly - cheapest and most-likely-to-reject first, so a request that
 * is going to be denied anyway does the least possible Redis work. The cooldown key stays claimed even if a later check
 * denies the request; that is intentional; see the design spec Section 6.
 *
 * <p>
 * Any Redis failure is treated as "allowed" (fail open) - password recovery must not become unavailable because of an
 * outage in infrastructure introduced solely for its own rate limiting.
 */
@Component
public class PasswordResetRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetRateLimiter.class);

    private static final long HOURLY_WINDOW_SECONDS = Duration.ofHours(1).toSeconds();

    /**
     * Atomic "increment, and set the window TTL only on the increment that created the key" - a plain INCR followed by
     * a separate EXPIRE NX is two round-trips, and a crash between them leaves a counter with no TTL, permanently
     * wedging that key. EVAL is atomic; Redis never interleaves another command with a running script.
     */
    private static final RedisScript<Long> HOURLY_CAP_SCRIPT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final PasswordResetRateLimiterProperties properties;

    public PasswordResetRateLimiter(StringRedisTemplate redisTemplate, PasswordResetRateLimiterProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public boolean allow(String email, String clientIp) {
        try {
            if (!claimCooldown(email)) {
                return false;
            }
            if (exceedsHourlyCap("password-reset:hourly:email:" + email, properties.getEmailHourlyCap())) {
                return false;
            }
            return !exceedsHourlyCap("password-reset:hourly:ip:" + clientIp, properties.getIpHourlyCap());
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable during password-reset rate-limit check, failing open", ex);
            return true;
        }
    }

    private boolean claimCooldown(String email) {
        Boolean claimed = redisTemplate.opsForValue().setIfAbsent("password-reset:cooldown:" + email, "1",
                Duration.ofSeconds(properties.getCooldownSeconds()));
        return Boolean.TRUE.equals(claimed);
    }

    private boolean exceedsHourlyCap(String key, int limit) {
        Long count = redisTemplate.execute(HOURLY_CAP_SCRIPT, List.of(key), String.valueOf(HOURLY_WINDOW_SECONDS));
        return count != null && count > limit;
    }
}
