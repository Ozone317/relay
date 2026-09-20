package com.example.relay.deliveryengine.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

public class RetryTierTest {

    @Test
    void forAttemptNo_returnsCorrectDelayForEachTier() {
        assertEquals(Duration.ofSeconds(30), RetryTier.forAttemptNo(2).getDelay());
        assertEquals(Duration.ofMinutes(2), RetryTier.forAttemptNo(3).getDelay());
        assertEquals(Duration.ofMinutes(10), RetryTier.forAttemptNo(4).getDelay());
        assertEquals(Duration.ofHours(1), RetryTier.forAttemptNo(5).getDelay());
        assertEquals(Duration.ofHours(6), RetryTier.forAttemptNo(6).getDelay());
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
