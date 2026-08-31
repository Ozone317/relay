package com.example.relay.message.exception;

import java.util.UUID;

public class NoActiveSubscribersException extends RuntimeException {

    public NoActiveSubscribersException(String name, UUID eventId) {
        super("No active subscriptions found for event " + name + " with id " + eventId);
    }
}
