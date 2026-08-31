package com.example.relay.message.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record MessageCreateDto(UUID eventId, JsonNode body) {
}
