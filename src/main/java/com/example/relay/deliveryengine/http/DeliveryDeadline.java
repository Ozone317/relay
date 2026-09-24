package com.example.relay.deliveryengine.http;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Monotonic deadline for one webhook exchange. */
public final class DeliveryDeadline {

    private final long deadlineNanos;
    private final LongSupplier nanoTime;

    private DeliveryDeadline(long deadlineNanos, LongSupplier nanoTime) {
        this.deadlineNanos = deadlineNanos;
        this.nanoTime = nanoTime;
    }

    public static DeliveryDeadline start(Duration budget, LongSupplier nanoTime) {
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(nanoTime, "nanoTime");
        if (budget.isNegative()) {
            throw new IllegalArgumentException("deadline budget must not be negative");
        }
        long started = nanoTime.getAsLong();
        long durationNanos;
        try {
            durationNanos = budget.toNanos();
        } catch (ArithmeticException exception) {
            durationNanos = Long.MAX_VALUE;
        }
        long deadline;
        try {
            deadline = Math.addExact(started, durationNanos);
        } catch (ArithmeticException exception) {
            deadline = durationNanos >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return new DeliveryDeadline(deadline, nanoTime);
    }

    public Duration remaining() {
        long remaining = deadlineNanos - nanoTime.getAsLong();
        if (remaining <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofNanos(remaining);
    }
}
