package com.example.relay.endpoint.api.dto;

import com.example.relay.endpoint.api.validation.ValidEndpointUpdate;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.URL;

@ValidEndpointUpdate
public record EndpointUpdateDto(String name, @URL(message = "URL must be a valid URL")
@Pattern(regexp = "^https?://.*$", message = "URL must use HTTP or HTTPS") String url, Boolean active) {
}
