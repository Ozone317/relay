package com.example.relay.endpoint.api.dto;

import java.time.Instant;
import java.util.UUID;

public record EndpointResponseDto(
    UUID id,
    String name,
    String url,
    boolean active,
    UUID appId,
    String signingSecret,
    Instant createdAt,
    Instant updatedAt
) {}
