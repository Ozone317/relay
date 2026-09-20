package com.example.relay.deliveryengine.retry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public final class RetryDelayCalculator {

    private final Clock clock;
    private final RetryProperties properties;
    private final RetryJitterSource jitterSource;

    public RetryDelayCalculator(Clock clock, RetryProperties properties, RetryJitterSource jitterSource) {
        this.clock = clock;
        this.properties = properties;
        this.jitterSource = jitterSource;
    }

    public Instant nextRetryAt(Duration baseDelay) {
        if (baseDelay.isNegative()) {
            throw new IllegalArgumentException("baseDelay must be >= 0");
        }

        long baseDelayNanos;
        try {
            baseDelayNanos = baseDelay.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "base-delay is too large to represent in nanoseconds", exception);
        }

        long maximumJitterNanos;
        try {
            maximumJitterNanos = BigDecimal.valueOf(baseDelayNanos)
                    .multiply(BigDecimal.valueOf(properties.getJitterFactor()))
                    .setScale(0, RoundingMode.FLOOR)
                    .longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "jitter-bound is too large to represent in nanoseconds", exception);
        }
        Duration jitter = jitterSource.next(Duration.ofNanos(maximumJitterNanos));
        if (jitter.isNegative() || jitter.compareTo(Duration.ofNanos(maximumJitterNanos)) > 0) {
            throw new IllegalStateException("Retry jitter source returned a value outside its inclusive bound");
        }
        return clock.instant().plus(baseDelay).plus(jitter);
    }
}
