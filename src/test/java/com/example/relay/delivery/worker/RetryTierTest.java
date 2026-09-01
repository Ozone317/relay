package com.example.relay.delivery.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.example.relay.delivery.config.RabbitMqConfig;

public class RetryTierTest {

    @Test
    void forAttemptNo_returnsCorrectRoutingKeyForEachTier() {
        assertEquals(RabbitMqConfig.WAIT_30S_ROUTING_KEY, RetryTier.forAttemptNo(2).getRoutingKey());
        assertEquals(RabbitMqConfig.WAIT_2M_ROUTING_KEY, RetryTier.forAttemptNo(3).getRoutingKey());
        assertEquals(RabbitMqConfig.WAIT_10M_ROUTING_KEY, RetryTier.forAttemptNo(4).getRoutingKey());
        assertEquals(RabbitMqConfig.WAIT_1H_ROUTING_KEY, RetryTier.forAttemptNo(5).getRoutingKey());
        assertEquals(RabbitMqConfig.WAIT_6H_ROUTING_KEY, RetryTier.forAttemptNo(6).getRoutingKey());
    }

    @Test
    void forAttemptNo_throws_whenNoTierExistsForThatAttemptNumber() {
        assertThrows(IllegalArgumentException.class, () -> RetryTier.forAttemptNo(1));
        assertThrows(IllegalArgumentException.class, () -> RetryTier.forAttemptNo(7));
    }

    @Test
    void maxAttempts_isSix() {
        assertEquals(6, RetryTier.MAX_ATTEMPTS);
    }
}
