package com.example.relay.endpoint.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.relay.app.domain.App;
import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.common.security.CustomUserDetailsService;
import com.example.relay.common.security.JwtService;
import com.example.relay.common.security.SecurityConfig;
import com.example.relay.endpoint.api.dto.EndpointCreateDto;
import com.example.relay.endpoint.api.dto.EndpointResponseDto;
import com.example.relay.endpoint.api.dto.EndpointUpdateDto;
import com.example.relay.endpoint.application.EndpointService;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.endpoint.exception.EndpointAlreadyExistsException;
import com.example.relay.endpoint.exception.EndpointNotFoundException;
import com.example.relay.endpoint.mapper.EndpointMapper;
import com.example.relay.environment.domain.Environment;
import com.example.relay.user.application.AuthService;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(EndpointController.class)
@Import(SecurityConfig.class)
public class EndpointControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EndpointService endpointService;

    @MockitoBean
    private EndpointMapper endpointMapper;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void create_createsAndReturnsEndpoint_ifItBelongsToTheAppAndUser() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        EndpointCreateDto request = new EndpointCreateDto("Production", "https://example.com/webhook");
        Endpoint endpoint = new Endpoint(request.name(), request.url(), "whsec_test", app);
        EndpointResponseDto response = new EndpointResponseDto(
            endpoint.getId(), endpoint.getName(), endpoint.getUrl(), endpoint.isActive(),
            app.getId(), endpoint.getSigningSecret(), endpoint.getCreatedAt(), endpoint.getUpdatedAt()
        );
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Stubs
        when(endpointService.create(request, app.getId(), env.getId(), user.getId())).thenReturn(endpoint);
        when(endpointMapper.toEndpointResponseDto(endpoint)).thenReturn(response);

        // Act
        mockMvc.perform(
            post("/api/v1/environments/{environmentId}/apps/{appId}/endpoints", env.getId(), app.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(response.id().toString()))
        .andExpect(jsonPath("$.name").value(response.name()))
        .andExpect(jsonPath("$.url").value(response.url()))
        .andExpect(jsonPath("$.signingSecret").value(response.signingSecret()));

        // Verify
        verify(endpointService).create(request, app.getId(), env.getId(), user.getId());
    }

    @Test
    void create_returnsConflict_whenEndpointWithSameNameAlreadyExists() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        EndpointCreateDto request = new EndpointCreateDto("Production", "https://example.com/webhook");
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Stub
        doThrow(new EndpointAlreadyExistsException(request.name()))
            .when(endpointService).create(request, app.getId(), env.getId(), user.getId());

        // Act + Assert
        mockMvc.perform(
            post("/api/v1/environments/{environmentId}/apps/{appId}/endpoints", env.getId(), app.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("Endpoint already exists with name: " + request.name()));

        // Verify
        verify(endpointMapper, never()).toEndpointResponseDto(any());
    }

    @Test
    void getById_returnsEndpoint_whenItBelongsToTheAppAndUser() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_test", app);
        EndpointResponseDto response = new EndpointResponseDto(
            endpoint.getId(), endpoint.getName(), endpoint.getUrl(), endpoint.isActive(),
            app.getId(), endpoint.getSigningSecret(), endpoint.getCreatedAt(), endpoint.getUpdatedAt()
        );
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Stub
        when(endpointService.getById(endpoint.getId(), app.getId(), env.getId(), user.getId())).thenReturn(endpoint);
        when(endpointMapper.toEndpointResponseDto(endpoint)).thenReturn(response);

        // Act
        mockMvc.perform(
            get("/api/v1/environments/{environmentId}/apps/{appId}/endpoints/{endpointId}", env.getId(), app.getId(), endpoint.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(response.id().toString()));

        // Verify
        verify(endpointService).getById(endpoint.getId(), app.getId(), env.getId(), user.getId());
    }

    @Test
    void getById_returns404_whenEndpointNotFound() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_test", app);
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Stub
        when(endpointService.getById(endpoint.getId(), app.getId(), env.getId(), user.getId()))
            .thenThrow(new EndpointNotFoundException(endpoint.getId()));

        // Act + Assert
        mockMvc.perform(
            get("/api/v1/environments/{environmentId}/apps/{appId}/endpoints/{endpointId}", env.getId(), app.getId(), endpoint.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Endpoint not found with id: " + endpoint.getId()));

        // Verify
        verify(endpointMapper, never()).toEndpointResponseDto(any());
    }

    @Test
    void getAll_returnsAllEndpoints_whenTheyBelongToTheAppAndUser() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        List<Endpoint> endpoints = List.of(
            new Endpoint("Production", "https://example.com/webhook", "whsec_1", app),
            new Endpoint("Staging", "https://staging.example.com/webhook", "whsec_2", app)
        );
        List<EndpointResponseDto> response = endpoints.stream()
            .map(e -> new EndpointResponseDto(e.getId(), e.getName(), e.getUrl(), e.isActive(), app.getId(), e.getSigningSecret(), e.getCreatedAt(), e.getUpdatedAt()))
            .toList();
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Stubs
        when(endpointService.getAll(app.getId(), env.getId(), user.getId())).thenReturn(endpoints);
        when(endpointMapper.toEndpointResponseDtoList(endpoints)).thenReturn(response);

        // Act
        mockMvc.perform(
            get("/api/v1/environments/{environmentId}/apps/{appId}/endpoints", env.getId(), app.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(response.get(0).id().toString()))
        .andExpect(jsonPath("$[1].id").value(response.get(1).id().toString()));

        // Verify
        verify(endpointService).getAll(app.getId(), env.getId(), user.getId());
    }

    @Test
    void getAll_returnsEmptyList_whenNoEndpointsExist() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Stubs
        when(endpointService.getAll(app.getId(), env.getId(), user.getId())).thenReturn(List.of());
        when(endpointMapper.toEndpointResponseDtoList(List.of())).thenReturn(List.of());

        // Act
        mockMvc.perform(
            get("/api/v1/environments/{environmentId}/apps/{appId}/endpoints", env.getId(), app.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
    }

    @Test
    void update_updatesAndReturnsEndpoint_whenItBelongsToTheAppAndUser() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_test", app);
        EndpointUpdateDto request = new EndpointUpdateDto("Updated", null, false);
        EndpointResponseDto response = new EndpointResponseDto(
            endpoint.getId(), "Updated", endpoint.getUrl(), false,
            app.getId(), endpoint.getSigningSecret(), endpoint.getCreatedAt(), endpoint.getUpdatedAt()
        );
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Stubs
        when(endpointService.update(request, endpoint.getId(), app.getId(), env.getId(), user.getId())).thenReturn(endpoint);
        when(endpointMapper.toEndpointResponseDto(endpoint)).thenReturn(response);

        // Act
        mockMvc.perform(
            patch("/api/v1/environments/{environmentId}/apps/{appId}/endpoints/{endpointId}", env.getId(), app.getId(), endpoint.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Updated"))
        .andExpect(jsonPath("$.active").value(false));

        // Verify
        verify(endpointService).update(request, endpoint.getId(), app.getId(), env.getId(), user.getId());
    }

    @Test
    void update_returns404_whenEndpointNotFound() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_test", app);
        EndpointUpdateDto request = new EndpointUpdateDto("Updated", null, null);
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Stub
        doThrow(new EndpointNotFoundException(endpoint.getId()))
            .when(endpointService).update(request, endpoint.getId(), app.getId(), env.getId(), user.getId());

        // Act + Assert
        mockMvc.perform(
            patch("/api/v1/environments/{environmentId}/apps/{appId}/endpoints/{endpointId}", env.getId(), app.getId(), endpoint.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Endpoint not found with id: " + endpoint.getId()));
    }

    @Test
    void delete_deletesTheEndpoint_whenItBelongsToTheAppAndUser() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_test", app);
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Act + Assert
        mockMvc.perform(
            delete("/api/v1/environments/{environmentId}/apps/{appId}/endpoints/{endpointId}", env.getId(), app.getId(), endpoint.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isNoContent());

        // Verify
        verify(endpointService).delete(endpoint.getId(), app.getId(), env.getId(), user.getId());
    }

    @Test
    void delete_returns404_whenEndpointNotFound() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_test", app);
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Stub
        doThrow(new EndpointNotFoundException(endpoint.getId()))
            .when(endpointService).delete(endpoint.getId(), app.getId(), env.getId(), user.getId());

        // Act + Assert
        mockMvc.perform(
            delete("/api/v1/environments/{environmentId}/apps/{appId}/endpoints/{endpointId}", env.getId(), app.getId(), endpoint.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Endpoint not found with id: " + endpoint.getId()));
    }
}
