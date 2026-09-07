package com.example.relay.attempt.exception;

import java.util.UUID;

public class ActiveAttemptAlreadyExistsException extends RuntimeException {

    public ActiveAttemptAlreadyExistsException(UUID messageId, UUID endpointId) {
        super("An active attempt already exists for message " + messageId + " and endpoint " + endpointId);
    }
}
