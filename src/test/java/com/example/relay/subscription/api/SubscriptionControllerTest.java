package com.example.relay.subscription.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.relay.app.domain.App;
import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.common.security.CustomUserDetailsService;
import com.example.relay.common.security.JwtService;
import com.example.relay.common.security.SecurityConfig;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.subscription.api.dto.SubscriptionResponseDto;
import com.example.relay.subscription.application.SubscriptionService;
import com.example.relay.subscription.domain.Subscription;
import com.example.relay.subscription.mapper.SubscriptionMapper;
import com.example.relay.user.application.AuthService;
import com.example.relay.user.domain.User;
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

@WebMvcTest(SubscriptionController.class)
@Import(SecurityConfig.class)
public class SubscriptionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubscriptionService subscriptionService;

    @MockitoBean
    private SubscriptionMapper subscriptionMapper;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void create_createsAndReturnsSubscription_viaPut() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Event event = new Event("user.created", app);
        Subscription subscription = new Subscription(app, event, endpoint);
        SubscriptionResponseDto response = new SubscriptionResponseDto(subscription.getId(), app.getId(), event.getId(),
                event.getName(), endpoint.getId(), subscription.getCreatedAt());
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Stubs
        when(subscriptionService.create(env.getId(), app.getId(), endpoint.getId(), event.getId(), user.getId()))
                .thenReturn(subscription);
        when(subscriptionMapper.toResponseDto(subscription)).thenReturn(response);

        // Act
        mockMvc.perform(
                put("/api/v1/environments/{environmentId}/apps/{appId}/endpoints/{endpointId}/subscriptions/{eventId}",
                        env.getId(), app.getId(), endpoint.getId(), event.getId()).with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(response.id().toString()))
                .andExpect(jsonPath("$.eventName").value(response.eventName()));

        // Verify
        verify(subscriptionService).create(env.getId(), app.getId(), endpoint.getId(), event.getId(), user.getId());
    }

    @Test
    void getAll_returnsAllSubscriptions_forGivenEndpoint() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Event event1 = new Event("user.created", app);
        Event event2 = new Event("user.deleted", app);
        List<Subscription> subscriptions =
                List.of(new Subscription(app, event1, endpoint), new Subscription(app, event2, endpoint));
        List<SubscriptionResponseDto> response = List.of(
                new SubscriptionResponseDto(subscriptions.get(0).getId(), app.getId(), event1.getId(), event1.getName(),
                        endpoint.getId(), subscriptions.get(0).getCreatedAt()),
                new SubscriptionResponseDto(subscriptions.get(1).getId(), app.getId(), event2.getId(), event2.getName(),
                        endpoint.getId(), subscriptions.get(1).getCreatedAt()));
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Stubs
        when(subscriptionService.getAll(env.getId(), app.getId(), endpoint.getId(), user.getId()))
                .thenReturn(subscriptions);
        when(subscriptionMapper.toResponseDtoList(subscriptions)).thenReturn(response);

        // Act
        mockMvc.perform(get("/api/v1/environments/{environmentId}/apps/{appId}/endpoints/{endpointId}/subscriptions",
                env.getId(), app.getId(), endpoint.getId()).with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventName").value(response.get(0).eventName()))
                .andExpect(jsonPath("$[1].eventName").value(response.get(1).eventName()));
    }

    @Test
    void getAll_returnsEmptyList_whenNoSubscriptionsExist() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Stubs
        when(subscriptionService.getAll(env.getId(), app.getId(), endpoint.getId(), user.getId()))
                .thenReturn(List.of());
        when(subscriptionMapper.toResponseDtoList(List.of())).thenReturn(List.of());

        // Act
        mockMvc.perform(get("/api/v1/environments/{environmentId}/apps/{appId}/endpoints/{endpointId}/subscriptions",
                env.getId(), app.getId(), endpoint.getId()).with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void delete_returnsNoContent_whenSubscriptionExists() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Event event = new Event("user.created", app);
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Act
        mockMvc.perform(delete(
                "/api/v1/environments/{environmentId}/apps/{appId}/endpoints/{endpointId}/subscriptions/{eventId}",
                env.getId(), app.getId(), endpoint.getId(), event.getId()).with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)).andExpect(status().isNoContent());

        // Verify
        verify(subscriptionService).delete(env.getId(), app.getId(), endpoint.getId(), event.getId(), user.getId());
    }

    @Test
    void delete_returnsNoContent_whenSubscriptionDoesNotExist() throws Exception {
        // Arrange - idempotent delete: service silently no-ops, controller still returns 204
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Event event = new Event("user.created", app);
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Act
        mockMvc.perform(delete(
                "/api/v1/environments/{environmentId}/apps/{appId}/endpoints/{endpointId}/subscriptions/{eventId}",
                env.getId(), app.getId(), endpoint.getId(), event.getId()).with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)).andExpect(status().isNoContent());
    }
}
