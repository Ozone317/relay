package com.example.relay.message.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record MessageCreateDto(@NotNull UUID eventId, @NotNull JsonNode body) {
}
