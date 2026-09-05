package com.example.relay.attempt.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.relay.app.domain.App;
import com.example.relay.attempt.api.dto.AttemptDetailDto;
import com.example.relay.attempt.api.dto.AttemptSummaryDto;
import com.example.relay.attempt.application.AttemptQueryService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.exception.AttemptNotFoundException;
import com.example.relay.attempt.mapper.AttemptMapper;
import com.example.relay.common.security.AuthProperties;
import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.common.security.CsrfHeaderFilter;
import com.example.relay.common.security.CustomUserDetailsService;
import com.example.relay.common.security.JwtService;
import com.example.relay.common.security.RefreshCookieFactory;
import com.example.relay.common.security.SecurityConfig;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.message.domain.Message;
import com.example.relay.user.application.AuthService;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(AttemptController.class)
@Import({SecurityConfig.class, AuthProperties.class, CsrfHeaderFilter.class, RefreshCookieFactory.class})
public class AttemptControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AttemptQueryService attemptQueryService;

    @MockitoBean
    private AttemptMapper attemptMapper;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void getAll_returnsPageOfAttempts_whenTheyBelongToTheAppAndUser() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_test", app);
        Message message = new Message(app, event, new ObjectMapper().readTree("{}"));
        Attempt attempt = new Attempt(app, message, endpoint, 1);
        AttemptSummaryDto summary = new AttemptSummaryDto(attempt.getId(), event.getName(), endpoint.getId(),
                endpoint.getName(), attempt.getAttemptNo(), attempt.getStatus(), null, null, attempt.getCreatedAt());
        Page<Attempt> attemptPage = new PageImpl<>(List.of(attempt));
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Stubs
        when(attemptQueryService.getPage(eq(app.getId()), eq(env.getId()), eq(user.getId()), any(), any(), any(),
                any(), any(Pageable.class))).thenReturn(attemptPage);
        when(attemptMapper.toSummaryDto(attempt)).thenReturn(summary);

        // Act
        mockMvc.perform(get("/api/v1/environments/{environmentId}/apps/{appId}/attempts", env.getId(), app.getId())
                .with(authentication(auth))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(summary.id().toString()))
                .andExpect(jsonPath("$.content[0].eventName").value(summary.eventName()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getAll_returnsEmptyPage_whenNoAttemptsMatch() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Stub
        when(attemptQueryService.getPage(eq(app.getId()), eq(env.getId()), eq(user.getId()), any(), any(), any(),
                any(), any(Pageable.class))).thenReturn(Page.empty());

        // Act
        mockMvc.perform(get("/api/v1/environments/{environmentId}/apps/{appId}/attempts", env.getId(), app.getId())
                .with(authentication(auth))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void getAll_passesEndpointStatusAndDateFilters_toTheService() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_test", app);
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-02T00:00:00Z");

        // Stub
        when(attemptQueryService.getPage(eq(app.getId()), eq(env.getId()), eq(user.getId()), eq(endpoint.getId()),
                eq(AttemptStatus.DEAD), eq(from), eq(to), any(Pageable.class))).thenReturn(Page.empty());

        // Act
        mockMvc.perform(get("/api/v1/environments/{environmentId}/apps/{appId}/attempts", env.getId(), app.getId())
                .param("endpointId", endpoint.getId().toString()).param("status", "DEAD")
                .param("createdFrom", from.toString()).param("createdTo", to.toString())
                .with(authentication(auth))).andExpect(status().isOk());
    }

    @Test
    void getById_returnsAttemptDetail_whenItBelongsToTheAppAndUser() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_test", app);
        Message message = new Message(app, event, new ObjectMapper().readTree("{\"amount\": 4999}"));
        Attempt attempt = new Attempt(app, message, endpoint, 1);
        AttemptDetailDto detail = new AttemptDetailDto(attempt.getId(), event.getName(), endpoint.getId(),
                endpoint.getName(), message.getId(), message.getBody(), attempt.getAttemptNo(), attempt.getStatus(),
                attempt.getResponseCode(), attempt.getResponseBody(), attempt.getLastError(), attempt.getLatencyMs(),
                attempt.getNextRetryAt(), attempt.getCreatedAt(), attempt.getUpdatedAt());
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Stubs
        when(attemptQueryService.getById(attempt.getId(), app.getId(), env.getId(), user.getId()))
                .thenReturn(attempt);
        when(attemptMapper.toDetailDto(attempt)).thenReturn(detail);

        // Act
        mockMvc.perform(get("/api/v1/environments/{environmentId}/apps/{appId}/attempts/{attemptId}", env.getId(),
                app.getId(), attempt.getId()).with(authentication(auth))).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(detail.id().toString()))
                .andExpect(jsonPath("$.messageId").value(detail.messageId().toString()))
                .andExpect(jsonPath("$.payload.amount").value(4999));
    }

    @Test
    void getById_returns404_whenAttemptNotFound() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        UUID attemptId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Stub
        when(attemptQueryService.getById(attemptId, app.getId(), env.getId(), user.getId()))
                .thenThrow(new AttemptNotFoundException(attemptId));

        // Act + Assert
        mockMvc.perform(get("/api/v1/environments/{environmentId}/apps/{appId}/attempts/{attemptId}", env.getId(),
                app.getId(), attemptId).with(authentication(auth))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Attempt not found with id " + attemptId));
    }
}
