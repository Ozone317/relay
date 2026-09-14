package com.example.relay.user.exception;

public class ExistingUnverifiedAccountException extends RuntimeException {

    private final String email;

    public ExistingUnverifiedAccountException(String email) {
        super("An unverified account already exists with email: " + email);
        this.email = email;
    }

    public String getEmail() {
        return email;
    }
}
