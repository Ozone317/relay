package com.example.relay.delivery.worker;

import java.time.Duration;

import com.example.relay.delivery.config.RabbitMqConfig;

public enum RetryTier {

    ATTEMPT_2(2, RabbitMqConfig.WAIT_30S_ROUTING_KEY, Duration.ofSeconds(30)),
    ATTEMPT_3(3, RabbitMqConfig.WAIT_2M_ROUTING_KEY, Duration.ofMinutes(2)),
    ATTEMPT_4(4, RabbitMqConfig.WAIT_10M_ROUTING_KEY, Duration.ofMinutes(10)),
    ATTEMPT_5(5, RabbitMqConfig.WAIT_1H_ROUTING_KEY, Duration.ofHours(1)),
    ATTEMPT_6(6, RabbitMqConfig.WAIT_6H_ROUTING_KEY, Duration.ofHours(6));

    public static final int MAX_ATTEMPTS = 6;

    private final int attemptNo;
    private final String routingKey;
    private final Duration delay;

    RetryTier(int attemptNo, String routingKey, Duration delay) {
        this.attemptNo = attemptNo;
        this.routingKey = routingKey;
        this.delay = delay;
    }

    public String getRoutingKey() {
        return routingKey;
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
