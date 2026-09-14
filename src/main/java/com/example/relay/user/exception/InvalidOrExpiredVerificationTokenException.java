package com.example.relay.user.exception;

public class InvalidOrExpiredVerificationTokenException extends RuntimeException {

    public InvalidOrExpiredVerificationTokenException(String message) {
        super(message);
    }
}
