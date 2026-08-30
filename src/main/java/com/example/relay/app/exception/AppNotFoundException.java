package com.example.relay.app.exception;

import java.util.UUID;

public class AppNotFoundException extends RuntimeException {

    public AppNotFoundException(UUID id) {
        super("App not found with id: " + id);
    }
}
