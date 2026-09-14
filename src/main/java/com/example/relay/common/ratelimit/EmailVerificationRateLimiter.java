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
 * A clone of PasswordResetRateLimiter, not a generalization of it - own key prefixes, own properties class, identical
 * ordering and fail-open policy. Also covers register()'s existing-unverified-account reissue path automatically,
 * since that path calls resend() directly (see AuthController.register(), Task 14) rather than a separate
 * rate-limited mechanism - see the design spec Section 6 for why sharing this exact limiter across both call sites is
 * load-bearing, not incidental.
 */
@Component
public class EmailVerificationRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationRateLimiter.class);

    private static final long HOURLY_WINDOW_SECONDS = Duration.ofHours(1).toSeconds();

    private static final RedisScript<Long> HOURLY_CAP_SCRIPT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final EmailVerificationRateLimiterProperties properties;

    public EmailVerificationRateLimiter(StringRedisTemplate redisTemplate,
            EmailVerificationRateLimiterProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public boolean allow(String email, String clientIp) {
        try {
            if (!claimCooldown(email)) {
                return false;
            }
            if (exceedsHourlyCap("email-verification:hourly:email:" + email, properties.getEmailHourlyCap())) {
                return false;
            }
            return !exceedsHourlyCap("email-verification:hourly:ip:" + clientIp, properties.getIpHourlyCap());
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable during email-verification rate-limit check, failing open", ex);
            return true;
        }
    }

    private boolean claimCooldown(String email) {
        Boolean claimed = redisTemplate.opsForValue().setIfAbsent("email-verification:cooldown:" + email, "1",
                Duration.ofSeconds(properties.getCooldownSeconds()));
        return Boolean.TRUE.equals(claimed);
    }

    private boolean exceedsHourlyCap(String key, int limit) {
        Long count = redisTemplate.execute(HOURLY_CAP_SCRIPT, List.of(key), String.valueOf(HOURLY_WINDOW_SECONDS));
        return count != null && count > limit;
    }
}
