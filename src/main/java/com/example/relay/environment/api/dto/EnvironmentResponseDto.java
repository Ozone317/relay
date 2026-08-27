package com.example.relay.environment.api.dto;

import java.time.Instant;
import java.util.UUID;

public record EnvironmentResponseDto (
    UUID id,
    String name,
    String description,
    Instant updatedAt
) {}
