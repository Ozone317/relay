package com.example.relay.common.exception;

import java.time.Instant;
import java.util.Map;

public record ApiError(int status, String message, Instant timestamp, Map<String, String> fieldErrors) {
    public static ApiError of(int status, String message) {
        return new ApiError(status, message, Instant.now(), null);
    }

    public static ApiError of(int status, String message, Map<String, String> fieldErrors) {
        return new ApiError(status, message, Instant.now(), fieldErrors);
    }
}
