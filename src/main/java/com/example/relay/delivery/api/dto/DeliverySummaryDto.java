package com.example.relay.delivery.api.dto;

import com.example.relay.attempt.domain.AttemptStatus;
import java.time.Instant;
import java.util.UUID;

public record DeliverySummaryDto(
    UUID id,
    String eventName,
    UUID endpointId,
    String endpointName,
    AttemptStatus status,
    long attemptCount,
    int latestAttemptNo,
    Integer responseCode,
    Long latencyMs,
    Instant createdAt,
    Instant lastAttemptAt
) {}
