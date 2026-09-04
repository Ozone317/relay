package com.example.relay.attempt.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.example.relay.attempt.domain.AttemptStatus;
import com.fasterxml.jackson.databind.JsonNode;

public record AttemptDetailDto(
    UUID id,
    String eventName,
    UUID endpointId,
    String endpointName,
    UUID messageId,
    JsonNode payload,
    int attemptNo,
    AttemptStatus status,
    Integer responseCode,
    String responseBody,
    String lastError,
    Long latencyMs,
    Instant nextRetryAt,
    Instant createdAt,
    Instant updatedAt
) {}
