package com.example.relay.event.api.dto;

import jakarta.validation.constraints.NotBlank;

public record EventCreateDto(@NotBlank(message = "Name cannot be blank") String name) {
}
