package com.example.relay.subscription.api.dto;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionResponseDto(UUID id, UUID appId, UUID eventId, String eventName, UUID endpointId,
        Instant createdAt) {
}
