package com.example.relay.deliveryengine.worker;

import java.time.Duration;

public enum RetryTier {

    ATTEMPT_2(2, Duration.ofSeconds(30)),
    ATTEMPT_3(3, Duration.ofMinutes(2)),
    ATTEMPT_4(4, Duration.ofMinutes(10)),
    ATTEMPT_5(5, Duration.ofHours(1)),
    ATTEMPT_6(6, Duration.ofHours(6));

    public static final int MAX_ATTEMPTS = 6;

    private final int attemptNo;
    private final Duration delay;

    RetryTier(int attemptNo, Duration delay) {
        this.attemptNo = attemptNo;
        this.delay = delay;
    }

    public Duration getDelay() {
        return delay;
    }

    public static RetryTier forAttemptNo(int attemptNo) {
        for (RetryTier tier : values()) {
            if (tier.attemptNo == attemptNo) {
                return tier;
            }
        }
        throw new IllegalArgumentException("No retry tier for attemptNo " + attemptNo);
    }
}
