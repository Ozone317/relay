package com.example.relay.message.api;

import com.example.relay.message.exception.InvalidIdempotencyKeyException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public record MessageIdempotencyKey(String value) {

    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,255}");

    public MessageIdempotencyKey {
        if (value == null || !TOKEN.matcher(value).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
    }

    public static Optional<MessageIdempotencyKey> parseHeaderValues(List<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return Optional.empty();
        }
        if (rawValues.size() != 1) {
            throw new InvalidIdempotencyKeyException();
        }
        return Optional.of(new MessageIdempotencyKey(rawValues.getFirst()));
    }
}
