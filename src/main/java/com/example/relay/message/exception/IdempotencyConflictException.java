package com.example.relay.message.exception;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("Idempotency-Key is already associated with a different message request");
    }
}
