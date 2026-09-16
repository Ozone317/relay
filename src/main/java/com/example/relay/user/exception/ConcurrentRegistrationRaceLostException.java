package com.example.relay.user.exception;

/**
 * Internal signal that this registration request lost a concurrent insert for the same email.
 * The winner already owns the account's initial verification token, so the controller must return
 * its generic registration response without invoking resend.
 */
public class ConcurrentRegistrationRaceLostException extends RuntimeException {

    private final String email;

    public ConcurrentRegistrationRaceLostException(String email) {
        super("A concurrent registration already created the unverified account for email: " + email);
        this.email = email;
    }

    public String getEmail() {
        return email;
    }
}
