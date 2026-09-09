package com.example.relay.delivery.api.dto;

import com.example.relay.attempt.domain.AttemptStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record DeliveryDetailDto(
    UUID id,
    String eventName,
    UUID endpointId,
    String endpointName,
    UUID messageId,
    JsonNode payload,
    AttemptStatus status,
    long attemptCount,
    int latestAttemptNo,
    Integer responseCode,
    Long latencyMs,
    Instant createdAt,
    Instant lastAttemptAt
) {}
