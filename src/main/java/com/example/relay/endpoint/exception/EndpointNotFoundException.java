package com.example.relay.endpoint.exception;

import java.util.UUID;

public class EndpointNotFoundException extends RuntimeException {

    public EndpointNotFoundException(UUID id) {
        super("Endpoint not found with id: " + id);
    }
}
