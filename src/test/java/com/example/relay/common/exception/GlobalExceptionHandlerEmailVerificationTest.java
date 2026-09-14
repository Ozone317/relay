package com.example.relay.common.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.relay.user.exception.EmailNotVerifiedException;
import com.example.relay.user.exception.InvalidOrExpiredVerificationTokenException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerEmailVerificationTest {

    private final GlobalExceptionHandler underTest =
            new GlobalExceptionHandler(new com.example.relay.common.security.RefreshCookieFactory(
                    new com.example.relay.common.security.AuthProperties()));

    @Test
    void handleEmailNotVerified_returns403() {
        ResponseEntity<ApiError> response =
                underTest.handleEmailNotVerified(new EmailNotVerifiedException("Email address is not verified"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("Email address is not verified", response.getBody().message());
    }

    @Test
    void handleInvalidOrExpiredVerificationToken_returns409WithAGenericMessage() {
        ResponseEntity<ApiError> response = underTest.handleInvalidOrExpiredVerificationToken(
                new InvalidOrExpiredVerificationTokenException("token was not found, has already been used, or has expired"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("Invalid or expired verification link.", response.getBody().message());
    }
}
