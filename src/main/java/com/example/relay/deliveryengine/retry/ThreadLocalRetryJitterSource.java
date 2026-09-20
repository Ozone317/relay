package com.example.relay.deliveryengine.retry;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

@Component
public class ThreadLocalRetryJitterSource implements RetryJitterSource {

    @Override
    public Duration next(Duration maximumInclusive) {
        if (maximumInclusive.isNegative()) {
            throw new IllegalArgumentException("maximumInclusive must be >= 0");
        }
        long maximumNanos;
        try {
            maximumNanos = maximumInclusive.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "maximum-inclusive is too large to represent in nanoseconds", exception);
        }
        if (maximumNanos == Long.MAX_VALUE) {
            return Duration.ofNanos(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE);
        }
        return Duration.ofNanos(ThreadLocalRandom.current().nextLong(maximumNanos + 1));
    }
}
