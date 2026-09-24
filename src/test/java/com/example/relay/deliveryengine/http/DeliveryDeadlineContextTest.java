package com.example.relay.deliveryengine.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class DeliveryDeadlineContextTest {

    @Test
    void deadlineUsesMonotonicRemainingBudget() {
        AtomicLong now = new AtomicLong(1_000_000L);
        DeliveryDeadline deadline = DeliveryDeadline.start(Duration.ofNanos(500L), now::get);
        assertEquals(Duration.ofNanos(500L), deadline.remaining());
        now.set(1_000_250L);
        assertEquals(Duration.ofNanos(250L), deadline.remaining());
        now.set(2_000_000L);
        assertEquals(Duration.ZERO, deadline.remaining());
    }

    @Test
    void normalCloseClearsCurrentDeadline() {
        DeliveryDeadline deadline = DeliveryDeadline.start(Duration.ofSeconds(1), System::nanoTime);
        try (DeliveryDeadlineContext.Scope ignored = DeliveryDeadlineContext.open(deadline)) {
            assertEquals(deadline, DeliveryDeadlineContext.current());
        }
        assertThrows(IllegalStateException.class, DeliveryDeadlineContext::current);
    }

    @Test
    void exceptionalCloseClearsCurrentDeadline() {
        DeliveryDeadline deadline = DeliveryDeadline.start(Duration.ofSeconds(1), System::nanoTime);
        assertThrows(IllegalArgumentException.class, () -> {
            try (DeliveryDeadlineContext.Scope ignored = DeliveryDeadlineContext.open(deadline)) {
                throw new IllegalArgumentException("boom");
            }
        });
        assertThrows(IllegalStateException.class, DeliveryDeadlineContext::current);
    }

    @Test
    void nestedScopeFailsWithoutReplacingOuterDeadline() {
        DeliveryDeadline outer = DeliveryDeadline.start(Duration.ofSeconds(1), System::nanoTime);
        DeliveryDeadline inner = DeliveryDeadline.start(Duration.ofSeconds(2), System::nanoTime);
        try (DeliveryDeadlineContext.Scope ignored = DeliveryDeadlineContext.open(outer)) {
            assertThrows(IllegalStateException.class, () -> DeliveryDeadlineContext.open(inner));
            assertEquals(outer, DeliveryDeadlineContext.current());
        }
    }

    @Test
    void missingScopeFails() {
        assertThrows(IllegalStateException.class, DeliveryDeadlineContext::current);
    }

    @Test
    void closedScopeCannotBeClosedTwice() {
        DeliveryDeadline deadline = DeliveryDeadline.start(Duration.ofSeconds(1), System::nanoTime);
        DeliveryDeadlineContext.Scope scope = DeliveryDeadlineContext.open(deadline);
        scope.close();
        assertThrows(IllegalStateException.class, scope::close);
    }

    @Test
    void secondScopeOnThreadCannotObserveFirstDeadline() {
        DeliveryDeadline first = DeliveryDeadline.start(Duration.ofSeconds(1), System::nanoTime);
        try (DeliveryDeadlineContext.Scope ignored = DeliveryDeadlineContext.open(first)) {
            assertEquals(first, DeliveryDeadlineContext.current());
        }
        DeliveryDeadline second = DeliveryDeadline.start(Duration.ofSeconds(2), System::nanoTime);
        try (DeliveryDeadlineContext.Scope ignored = DeliveryDeadlineContext.open(second)) {
            assertEquals(second, DeliveryDeadlineContext.current());
        }
        assertDoesNotThrow(() -> {
            Thread thread = new Thread(() -> assertThrows(IllegalStateException.class, DeliveryDeadlineContext::current));
            thread.start();
            thread.join();
        });
    }
}
