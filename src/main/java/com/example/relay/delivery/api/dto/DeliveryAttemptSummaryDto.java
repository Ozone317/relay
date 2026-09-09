package com.example.relay.delivery.api.dto;

import com.example.relay.attempt.domain.AttemptStatus;
import java.time.Instant;
import java.util.UUID;

public record DeliveryAttemptSummaryDto(
    UUID id,
    int attemptNo,
    AttemptStatus status,
    Integer responseCode,
    Long latencyMs,
    Instant createdAt
) {}
