package com.example.relay.attempt.exception;

import java.util.UUID;

public class AttemptNotFoundException extends RuntimeException {

    public AttemptNotFoundException(UUID id) {
        super("Attempt not found with id " + id);
    }
}
