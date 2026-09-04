package com.example.relay.attempt.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.example.relay.attempt.domain.AttemptStatus;

public record AttemptSummaryDto(
    UUID id,
    String eventName,
    UUID endpointId,
    String endpointName,
    int attemptNo,
    AttemptStatus status,
    Integer responseCode,
    Long latencyMs,
    Instant createdAt
) {}
