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
        long maximumNanos = maximumInclusive.toNanos();
        return Duration.ofNanos(ThreadLocalRandom.current().nextLong(maximumNanos + 1));
    }
}
