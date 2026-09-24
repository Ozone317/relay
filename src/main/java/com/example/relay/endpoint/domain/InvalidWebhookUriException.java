package com.example.relay.endpoint.domain;

public final class InvalidWebhookUriException extends RuntimeException {

    public InvalidWebhookUriException(String message) {
        super(message);
    }

    public InvalidWebhookUriException(String message, Throwable cause) {
        super(message, cause);
    }
}
