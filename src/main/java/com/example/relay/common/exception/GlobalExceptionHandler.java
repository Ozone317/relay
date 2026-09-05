package com.example.relay.common.exception;

import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.attempt.exception.AttemptNotFoundException;
import com.example.relay.common.security.RefreshCookieFactory;
import com.example.relay.endpoint.exception.EndpointAlreadyExistsException;
import com.example.relay.endpoint.exception.EndpointNotFoundException;
import com.example.relay.environment.exception.EnvironmentNotFoundException;
import com.example.relay.event.exception.EventAlreadyExistsException;
import com.example.relay.event.exception.EventNotFoundException;
import com.example.relay.message.exception.NoActiveSubscribersException;
import com.example.relay.user.exception.InvalidRefreshTokenException;
import com.example.relay.user.exception.UserAlreadyExistsException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * The single answer every rejected refresh token gets, whatever the actual cause.
     */
    private static final String INVALID_REFRESH_TOKEN = "Invalid refresh token";

    private final RefreshCookieFactory refreshCookieFactory;

    public GlobalExceptionHandler(RefreshCookieFactory refreshCookieFactory) {
        this.refreshCookieFactory = refreshCookieFactory;
    }

    /**
     * Answers every rejected refresh token identically.
     *
     * <p>
     * The throw sites distinguish "not recognised" from "has been revoked" from "has expired", and that distinction is
     * worth keeping in the log. It must not reach the client: the caller here is unauthenticated by definition, so
     * returning the specific reason let anyone holding a stolen token discover whether the victim had logged out. The
     * design spec refuses to leak exactly that on /logout; passing ex.getMessage() through here leaked it anyway.
     */
    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ApiError> handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
        log.warn("Refresh token rejected: {}", ex.getMessage());
        ApiError error = ApiError.of(HttpStatus.UNAUTHORIZED.value(), INVALID_REFRESH_TOKEN);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, refreshCookieFactory.clear().toString()).body(error);
    }

    @ExceptionHandler(EnvironmentNotFoundException.class)
    public ResponseEntity<ApiError> handleEnvironmentNotFound(EnvironmentNotFoundException ex) {
        ApiError error = ApiError.of(HttpStatus.NOT_FOUND.value(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleEnvironmentValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> fieldErrors.put(fe.getField(), fe.getDefaultMessage()));
        ApiError error = ApiError.of(HttpStatus.BAD_REQUEST.value(), "Validation failed", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidLoginCredentials(BadCredentialsException ex) {
        ApiError error = ApiError.of(HttpStatus.UNAUTHORIZED.value(), "Invalid email or password");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleUserAlreadyExistsException(UserAlreadyExistsException ex) {
        ApiError error = ApiError.of(HttpStatus.CONFLICT.value(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(AppNotFoundException.class)
    public ResponseEntity<ApiError> handleAppNotFoundException(AppNotFoundException ex) {
        ApiError error = ApiError.of(HttpStatus.NOT_FOUND.value(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(EventAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleEventAlreadyExistsException(EventAlreadyExistsException ex) {
        ApiError error = ApiError.of(HttpStatus.CONFLICT.value(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<ApiError> handleEventNotFoundException(EventNotFoundException ex) {
        ApiError error = ApiError.of(HttpStatus.NOT_FOUND.value(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(EndpointNotFoundException.class)
    public ResponseEntity<ApiError> handleEndpointNotFoundException(EndpointNotFoundException ex) {
        ApiError error = ApiError.of(HttpStatus.NOT_FOUND.value(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(EndpointAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleEndpointAlreadyExistsException(EndpointAlreadyExistsException ex) {
        ApiError error = ApiError.of(HttpStatus.CONFLICT.value(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(NoActiveSubscribersException.class)
    public ResponseEntity<ApiError> handleNoActiveSubscribersExeption(NoActiveSubscribersException ex) {
        ApiError error = ApiError.of(HttpStatus.UNPROCESSABLE_ENTITY.value(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error);
    }

    @ExceptionHandler(AttemptNotFoundException.class)
    public ResponseEntity<ApiError> handleAttemptNotFoundException(AttemptNotFoundException ex) {
        ApiError error = ApiError.of(HttpStatus.NOT_FOUND.value(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }
}
