package com.example.relay.message.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.relay.app.domain.App;
import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.common.security.CustomUserDetailsService;
import com.example.relay.common.security.JwtService;
import com.example.relay.common.security.SecurityConfig;
import com.example.relay.delivery.publisher.AttemptPublisher;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.event.exception.EventNotFoundException;
import com.example.relay.message.api.dto.MessageCreateDto;
import com.example.relay.message.api.dto.MessageCreateResult;
import com.example.relay.message.api.dto.MessageResponseDto;
import com.example.relay.message.application.MessageService;
import com.example.relay.message.domain.Message;
import com.example.relay.message.exception.NoActiveSubscribersException;
import com.example.relay.message.mapper.MessageMapper;
import com.example.relay.user.application.AuthService;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MessageController.class)
@Import(SecurityConfig.class)
public class MessageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MessageService messageService;

    @MockitoBean
    private MessageMapper messageMapper;

    @MockitoBean
    private AttemptPublisher attemptPublisher;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    private Authentication authFor(User user) {
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void create_createsMessageAndPublishesEveryAttempt_returnsCreated() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpointOne = new Endpoint("EP 1", "https://example.com/one", "whsec_1", app);
        Endpoint endpointTwo = new Endpoint("EP 2", "https://example.com/two", "whsec_2", app);
        Event event = new Event("payment.completed", app);
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        MessageCreateDto request = new MessageCreateDto(event.getId(), body);
        Message message = new Message(app, event, body);
        List<Attempt> attempts =
                List.of(new Attempt(app, message, endpointOne, 1), new Attempt(app, message, endpointTwo, 1));
        MessageCreateResult result = new MessageCreateResult(message, attempts);
        MessageResponseDto response = new MessageResponseDto(message.getId(), app.getId(), event.getId(),
                event.getName(), body, message.getCreatedAt());

        // Stubs
        when(messageService.create(request, app.getId(), env.getId(), user.getId())).thenReturn(result);
        when(messageMapper.toResponseDto(message)).thenReturn(response);

        // Act
        mockMvc.perform(post("/api/v1/environments/{environmentId}/apps/{appId}/messages", env.getId(), app.getId())
                .with(authentication(authFor(user))).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(response.id().toString()))
                .andExpect(jsonPath("$.eventId").value(response.eventId().toString()))
                .andExpect(jsonPath("$.eventName").value(response.eventName()));

        // Verify
        verify(attemptPublisher).publish(attempts.get(0).getId());
        verify(attemptPublisher).publish(attempts.get(1).getId());
    }

    @Test
    void create_returnsBadRequest_whenEventIdIsMissing() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        String invalidPayload = objectMapper.writeValueAsString(new MessageCreateDto(null, body));

        // Act + Assert
        mockMvc.perform(
                post("/api/v1/environments/{environmentId}/apps/{appId}/messages", UUID.randomUUID(), UUID.randomUUID())
                        .with(authentication(authFor(user))).contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest());

        // Verify
        verify(messageService, never()).create(any(), any(), any(), any());
        verify(attemptPublisher, never()).publish(any());
    }

    @Test
    void create_returnsBadRequest_whenBodyIsMissing() throws Exception {
        // Arrange
        // Built by hand, omitting the "body" key entirely - serializing MessageCreateDto with a
        // Java null body instead would produce "body": null, which Jackson binds to NullNode (a
        // non-null JsonNode) rather than Java null, so @NotNull would never fire on it.
        User user = new User("test@mail.com", "passwordHash");
        String invalidPayload = "{\"eventId\":\"" + UUID.randomUUID() + "\"}";

        // Act + Assert
        mockMvc.perform(
                post("/api/v1/environments/{environmentId}/apps/{appId}/messages", UUID.randomUUID(), UUID.randomUUID())
                        .with(authentication(authFor(user))).contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest());

        // Verify
        verify(messageService, never()).create(any(), any(), any(), any());
        verify(attemptPublisher, never()).publish(any());
    }

    @Test
    void create_returnsNotFound_whenAppDoesNotExist() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        UUID envId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        MessageCreateDto request = new MessageCreateDto(UUID.randomUUID(), body);

        // Stub
        doThrow(new AppNotFoundException(appId)).when(messageService).create(request, appId, envId, user.getId());

        // Act + Assert
        mockMvc.perform(post("/api/v1/environments/{environmentId}/apps/{appId}/messages", envId, appId)
                .with(authentication(authFor(user))).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("App not found with id: " + appId));

        // Verify
        verify(attemptPublisher, never()).publish(any());
    }

    @Test
    void create_returnsNotFound_whenEventDoesNotExist() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        UUID envId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        MessageCreateDto request = new MessageCreateDto(eventId, body);

        // Stub
        doThrow(new EventNotFoundException(eventId)).when(messageService).create(request, appId, envId, user.getId());

        // Act + Assert
        mockMvc.perform(post("/api/v1/environments/{environmentId}/apps/{appId}/messages", envId, appId)
                .with(authentication(authFor(user))).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Event not found with id: " + eventId));

        // Verify
        verify(attemptPublisher, never()).publish(any());
    }

    @Test
    void create_returnsUnprocessableEntity_whenNoActiveSubscribersExist() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        UUID envId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        MessageCreateDto request = new MessageCreateDto(eventId, body);

        // Stub
        doThrow(new NoActiveSubscribersException("payment.completed", eventId)).when(messageService).create(request,
                appId, envId, user.getId());

        // Act + Assert
        mockMvc.perform(post("/api/v1/environments/{environmentId}/apps/{appId}/messages", envId, appId)
                .with(authentication(authFor(user))).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))).andExpect(status().isUnprocessableEntity());

        // Verify
        verify(attemptPublisher, never()).publish(any());
    }
}
