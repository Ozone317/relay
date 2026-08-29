package com.example.relay.endpoint.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.example.relay.app.domain.App;
import com.example.relay.endpoint.api.dto.EndpointCreateDto;
import com.example.relay.endpoint.api.dto.EndpointResponseDto;
import com.example.relay.endpoint.domain.Endpoint;

import lombok.NoArgsConstructor;

@Component
@NoArgsConstructor
public class EndpointMapper {

    public Endpoint toEntity(EndpointCreateDto request, App app, String signingSecret) {
        return new Endpoint(
            request.name(),
            request.url(),
            signingSecret,
            app
        );
    }

    public EndpointResponseDto toEndpointResponseDto(Endpoint endpoint) {
        return new EndpointResponseDto(
            endpoint.getId(),
            endpoint.getName(),
            endpoint.getUrl(),
            endpoint.isActive(),
            endpoint.getApp().getId(),
            endpoint.getSigningSecret(),
            endpoint.getCreatedAt(),
            endpoint.getUpdatedAt()
        );
    }

    public List<EndpointResponseDto> toEndpointResponseDtoList(List<Endpoint> endpoints) {
        return endpoints.stream()
            .map(this::toEndpointResponseDto)
            .toList();
    }
}
