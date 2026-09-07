package com.example.relay.attempt.exception;

import java.util.UUID;

public class ReplayEndpointInactiveException extends RuntimeException {

    public ReplayEndpointInactiveException(UUID endpointId) {
        super("Cannot replay: endpoint " + endpointId + " is inactive");
    }
}
