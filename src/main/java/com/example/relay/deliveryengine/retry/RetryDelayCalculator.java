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

        long maximumJitterNanos = BigDecimal.valueOf(baseDelay.toNanos())
                .multiply(BigDecimal.valueOf(properties.getJitterFactor()))
                .setScale(0, RoundingMode.FLOOR)
                .longValueExact();
        Duration jitter = jitterSource.next(Duration.ofNanos(maximumJitterNanos));
        if (jitter.isNegative() || jitter.compareTo(Duration.ofNanos(maximumJitterNanos)) > 0) {
            throw new IllegalStateException("Retry jitter source returned a value outside its inclusive bound");
        }
        return clock.instant().plus(baseDelay).plus(jitter);
    }
}
