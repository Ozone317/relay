package com.example.relay.message.exception;

public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key must be a 1-255 character RFC token");
    }
}
