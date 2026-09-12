package com.example.relay.user.exception;

public class InvalidOrExpiredResetTokenException extends RuntimeException {

    public InvalidOrExpiredResetTokenException(String message) {
        super(message);
    }
}
