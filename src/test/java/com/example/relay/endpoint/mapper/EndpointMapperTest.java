package com.example.relay.endpoint.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.app.domain.App;
import com.example.relay.endpoint.api.dto.EndpointCreateDto;
import com.example.relay.endpoint.api.dto.EndpointResponseDto;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.user.domain.User;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EndpointMapperTest {

    private EndpointMapper underTest;

    @BeforeEach
    void setUp() {
        underTest = new EndpointMapper();
    }

    @Test
    void toEntity_createsAndReturnsAnEndpoint() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);

        EndpointCreateDto request = new EndpointCreateDto("Production", "https://example.com/webhook");

        // Act
        Endpoint result = underTest.toEntity(request, app, "whsec_test123");

        // Assert
        assertEquals("Production", result.getName());
        assertEquals("https://example.com/webhook", result.getUrl());
        assertEquals("whsec_test123", result.getSigningSecret());
        assertEquals(app, result.getApp());
        assertTrue(result.isActive());
    }

    @Test
    void toEndpointResponseDto_mapsEndpointToEndpointResponseDtoAndReturns() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_test123", app);

        // Act
        EndpointResponseDto result = underTest.toEndpointResponseDto(endpoint);

        // Assert
        assertEquals(endpoint.getId(), result.id());
        assertEquals(endpoint.getName(), result.name());
        assertEquals(endpoint.getUrl(), result.url());
        assertEquals(endpoint.isActive(), result.active());
        assertEquals(endpoint.getApp().getId(), result.appId());
        assertEquals(endpoint.getSigningSecret(), result.signingSecret());
        assertEquals(endpoint.getCreatedAt(), result.createdAt());
        assertEquals(endpoint.getUpdatedAt(), result.updatedAt());
    }

    @Test
    void toEndpointResponseDtoList_mapsListOfEndpointsToListOfEndpointResponseDto() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        List<Endpoint> endpoints =
                List.of(new Endpoint("Production", "https://example.com/webhook", "whsec_test1", app),
                        new Endpoint("Staging", "https://staging.example.com/webhook", "whsec_test2", app));

        // Act
        List<EndpointResponseDto> result = underTest.toEndpointResponseDtoList(endpoints);

        // Assert
        assertEquals(endpoints.size(), result.size());
        assertEquals(endpoints.get(0).getId(), result.get(0).id());
        assertEquals(endpoints.get(1).getId(), result.get(1).id());
        assertEquals(endpoints.get(0).getName(), result.get(0).name());
        assertEquals(endpoints.get(1).getName(), result.get(1).name());
        assertEquals(endpoints.get(0).getUrl(), result.get(0).url());
        assertEquals(endpoints.get(1).getUrl(), result.get(1).url());
    }
}
