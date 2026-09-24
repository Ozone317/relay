package com.example.relay.endpoint.api.dto;

import com.example.relay.endpoint.api.validation.ValidEndpointUpdate;
import com.example.relay.endpoint.api.validation.ValidWebhookUrl;

@ValidEndpointUpdate
public record EndpointUpdateDto(String name, @ValidWebhookUrl String url, Boolean active) {
}
