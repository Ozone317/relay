package com.example.relay.message.infrastructure;

import java.time.Instant;
import java.util.UUID;

public record CommittedMessageIdempotency(UUID messageId, Instant acceptedAt, short fingerprintVersion,
        boolean fingerprintMatches) {
}
