package com.example.relay.app.api.dto;

import java.time.Instant;
import java.util.UUID;

public record AppResponseDto(
    UUID id,
    String name,
    UUID environmentId,
    Instant createdAt
) {}
