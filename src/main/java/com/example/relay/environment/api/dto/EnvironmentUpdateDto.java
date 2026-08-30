package com.example.relay.environment.api.dto;

import jakarta.validation.constraints.Size;

public record EnvironmentUpdateDto(
        @Size(max = 500, message = "Description cannot exceed 500 characters") String description) {
}
