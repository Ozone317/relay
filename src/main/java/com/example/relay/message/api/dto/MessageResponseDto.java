package com.example.relay.message.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record MessageResponseDto(UUID id, UUID appId, UUID eventId, String eventName, JsonNode body,
        Instant createdAt) {
}
