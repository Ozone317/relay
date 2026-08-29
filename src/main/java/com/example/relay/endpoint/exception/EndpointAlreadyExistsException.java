package com.example.relay.endpoint.exception;

public class EndpointAlreadyExistsException extends RuntimeException {

    public EndpointAlreadyExistsException(String name) {
        super("Endpoint already exists with name: " + name);
    }
}
