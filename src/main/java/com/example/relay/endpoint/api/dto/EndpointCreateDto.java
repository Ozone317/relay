package com.example.relay.endpoint.api.dto;

import org.hibernate.validator.constraints.URL;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record EndpointCreateDto(
    @NotBlank
    String name,

    @NotBlank
    @URL(message = "URL must be a valid URL")
    @Pattern(
        regexp = "^https?://.*$",
        message = "URL must use HTTP or HTTPS"
    )
    String url
) {}
