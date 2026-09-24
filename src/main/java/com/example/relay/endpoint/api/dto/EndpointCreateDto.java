package com.example.relay.endpoint.api.dto;

import com.example.relay.endpoint.api.validation.ValidWebhookUrl;
import jakarta.validation.constraints.NotBlank;

public record EndpointCreateDto(@NotBlank String name, @NotBlank @ValidWebhookUrl String url) {
}
