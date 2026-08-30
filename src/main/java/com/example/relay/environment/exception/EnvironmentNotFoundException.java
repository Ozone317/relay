package com.example.relay.environment.exception;

import java.util.UUID;

public class EnvironmentNotFoundException extends RuntimeException {

    public EnvironmentNotFoundException(UUID id) {
        super("Environment not found with id: " + id);
    }
}
