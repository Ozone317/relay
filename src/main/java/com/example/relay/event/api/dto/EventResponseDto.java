package com.example.relay.event.api.dto;

import java.time.Instant;
import java.util.UUID;

public record EventResponseDto(UUID id, String name, UUID appId, Instant createdAt, long subscriberCount) {
}
