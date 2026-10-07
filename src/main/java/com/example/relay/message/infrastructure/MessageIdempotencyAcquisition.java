package com.example.relay.message.infrastructure;

import java.time.Instant;
import java.util.UUID;

public record MessageIdempotencyAcquisition(UUID messageId, Instant acceptedAt) {
}
