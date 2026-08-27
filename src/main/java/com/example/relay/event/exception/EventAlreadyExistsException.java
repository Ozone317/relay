package com.example.relay.event.exception;

public class EventAlreadyExistsException extends RuntimeException {

    public EventAlreadyExistsException(String name) {
        super("Event already exists with name: " + name);
    }
}
