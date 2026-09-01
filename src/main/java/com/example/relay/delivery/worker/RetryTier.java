package com.example.relay.delivery.worker;

import com.example.relay.delivery.config.RabbitMqConfig;

public enum RetryTier {

    ATTEMPT_2(2, RabbitMqConfig.WAIT_30S_ROUTING_KEY),
    ATTEMPT_3(3, RabbitMqConfig.WAIT_2M_ROUTING_KEY),
    ATTEMPT_4(4, RabbitMqConfig.WAIT_10M_ROUTING_KEY),
    ATTEMPT_5(5, RabbitMqConfig.WAIT_1H_ROUTING_KEY),
    ATTEMPT_6(6, RabbitMqConfig.WAIT_6H_ROUTING_KEY);

    public static final int MAX_ATTEMPTS = 6;

    private final int attemptNo;
    private final String routingKey;

    RetryTier(int attemptNo, String routingKey) {
        this.attemptNo = attemptNo;
        this.routingKey = routingKey;
    }

    public String getRoutingKey() {
        return routingKey;
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
