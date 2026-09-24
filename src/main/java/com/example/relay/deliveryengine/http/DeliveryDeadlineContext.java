package com.example.relay.deliveryengine.http;

import java.util.Objects;

/** Narrow resolver-SPI adapter for the current synchronous Apache delivery callback. */
public final class DeliveryDeadlineContext {

    private static final ThreadLocal<DeliveryDeadline> CURRENT = new ThreadLocal<>();

    private DeliveryDeadlineContext() {
    }

    public static Scope open(DeliveryDeadline deadline) {
        Objects.requireNonNull(deadline, "deadline");
        if (CURRENT.get() != null) {
            throw new IllegalStateException("A delivery deadline scope is already open");
        }
        CURRENT.set(deadline);
        return new Scope(deadline);
    }

    public static DeliveryDeadline current() {
        DeliveryDeadline deadline = CURRENT.get();
        if (deadline == null) {
            throw new IllegalStateException("No delivery deadline scope is open");
        }
        return deadline;
    }

    public static final class Scope implements AutoCloseable {

        private final DeliveryDeadline deadline;
        private boolean closed;

        private Scope(DeliveryDeadline deadline) {
            this.deadline = deadline;
        }

        @Override
        public void close() {
            if (closed) {
                throw new IllegalStateException("Delivery deadline scope is already closed");
            }
            if (CURRENT.get() != deadline) {
                throw new IllegalStateException("Delivery deadline scope is not current");
            }
            closed = true;
            CURRENT.remove();
        }
    }
}
