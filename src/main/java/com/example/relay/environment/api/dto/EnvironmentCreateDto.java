package com.example.relay.environment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EnvironmentCreateDto(@NotBlank(message = "Name cannot be blank") String name,
        @Size(max = 500, message = "Description cannot exceed 500 characters") String description) {
}
