package com.example.relay.app.api.dto;

import jakarta.validation.constraints.NotBlank;

public record AppCreateDto(@NotBlank(message = "Name cannot be blank") String name) {
}
