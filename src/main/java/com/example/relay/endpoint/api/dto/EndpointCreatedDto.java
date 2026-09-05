package com.example.relay.endpoint.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Returned ONLY from endpoint creation. This is the one and only time the signing secret is ever
 * sent to a client (PRD FR-3.2); every read goes through EndpointResponseDto, which has no such
 * component.
 */
public record EndpointCreatedDto(UUID id, String name, String url, boolean active, UUID appId, String signingSecret,
        Instant createdAt, Instant updatedAt) {
}
