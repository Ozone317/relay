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
import com.example.relay.common.security.AuthProperties;
import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.common.security.CsrfHeaderFilter;
import com.example.relay.common.security.CustomUserDetailsService;
import com.example.relay.common.security.JwtService;
import com.example.relay.common.security.RefreshCookieFactory;
import com.example.relay.common.security.SecurityConfig;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.event.exception.EventNotFoundException;
import com.example.relay.message.api.dto.MessageCreateDto;
import com.example.relay.message.api.dto.MessageCreateResult;
import com.example.relay.message.api.dto.MessageResponseDto;
import com.example.relay.message.application.MessageService;
import com.example.relay.message.domain.Message;
import com.example.relay.message.exception.IdempotencyConflictException;
import com.example.relay.message.exception.NoActiveSubscribersException;
import com.example.relay.message.mapper.MessageMapper;
import com.example.relay.user.application.AuthService;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
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
@Import({SecurityConfig.class, AuthProperties.class, CsrfHeaderFilter.class, RefreshCookieFactory.class})
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
    void create_createsMessage_returnsCreated() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        MessageCreateDto request = new MessageCreateDto(event.getId(), body);
        Message message = new Message(app, event, body);
        MessageCreateResult result = new MessageCreateResult(message);
        MessageResponseDto response = new MessageResponseDto(message.getId(), app.getId(), event.getId(),
                event.getName(), body, message.getCreatedAt());

        // Stubs
        when(messageService.create(request, Optional.empty(), app.getId(), env.getId(), user.getId()))
                .thenReturn(result);
        when(messageMapper.toResponseDto(message)).thenReturn(response);

        // Act
        mockMvc.perform(post("/api/v1/environments/{environmentId}/apps/{appId}/messages", env.getId(), app.getId())
                .with(authentication(authFor(user))).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(response.id().toString()))
                .andExpect(jsonPath("$.eventId").value(response.eventId().toString()))
                .andExpect(jsonPath("$.eventName").value(response.eventName()));

        verify(messageService).create(request, Optional.empty(), app.getId(), env.getId(), user.getId());

        // Publication is owned by ReadyWorkDispatcher after the controller returns.
        // The WebMvc test verifies the controller's 201 response and service interaction only.
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
        verify(messageService, never()).create(any(), any(), any(), any(), any());
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
        verify(messageService, never()).create(any(), any(), any(), any(), any());
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
        doThrow(new AppNotFoundException(appId)).when(messageService).create(request, Optional.empty(), appId, envId,
                user.getId());

        // Act + Assert
        mockMvc.perform(post("/api/v1/environments/{environmentId}/apps/{appId}/messages", envId, appId)
                .with(authentication(authFor(user))).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("App not found with id: " + appId));

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
        doThrow(new EventNotFoundException(eventId)).when(messageService).create(request, Optional.empty(), appId,
                envId, user.getId());

        // Act + Assert
        mockMvc.perform(post("/api/v1/environments/{environmentId}/apps/{appId}/messages", envId, appId)
                .with(authentication(authFor(user))).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Event not found with id: " + eventId));

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
                Optional.empty(), appId, envId, user.getId());

        // Act + Assert
        mockMvc.perform(post("/api/v1/environments/{environmentId}/apps/{appId}/messages", envId, appId)
                .with(authentication(authFor(user))).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))).andExpect(status().isUnprocessableEntity());

    }

    @Test
    void create_passesValidIdempotencyKey_whenHeaderIsPresent() throws Exception {
        User user = new User("test@mail.com", "passwordHash");
        UUID envId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        ObjectNode body = objectMapper.createObjectNode().put("amount", 4999);
        MessageCreateDto request = new MessageCreateDto(UUID.randomUUID(), body);
        MessageCreateResult result = new MessageCreateResult(null);
        when(messageService.create(org.mockito.ArgumentMatchers.eq(request), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(appId), org.mockito.ArgumentMatchers.eq(envId),
                org.mockito.ArgumentMatchers.eq(user.getId()))).thenReturn(result);

        mockMvc.perform(post("/api/v1/environments/{environmentId}/apps/{appId}/messages", envId, appId)
                .with(authentication(authFor(user))).header("Idempotency-Key", "Case-Sensitive_09")
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        verify(messageService).create(org.mockito.ArgumentMatchers.eq(request),
                org.mockito.ArgumentMatchers
                        .argThat(key -> key.isPresent() && key.orElseThrow().value().equals("Case-Sensitive_09")),
                org.mockito.ArgumentMatchers.eq(appId), org.mockito.ArgumentMatchers.eq(envId),
                org.mockito.ArgumentMatchers.eq(user.getId()));
    }

    @Test
    void create_returnsBadRequestAndDoesNotCallService_whenIdempotencyKeyIsInvalid() throws Exception {
        for (String value : List.of("", "has space", "a".repeat(256))) {
            User user = new User("test@mail.com", "passwordHash");
            ObjectNode body = objectMapper.createObjectNode().put("amount", 4999);
            String payload = objectMapper.writeValueAsString(new MessageCreateDto(UUID.randomUUID(), body));

            mockMvc.perform(post("/api/v1/environments/{environmentId}/apps/{appId}/messages", UUID.randomUUID(),
                    UUID.randomUUID()).with(authentication(authFor(user))).header("Idempotency-Key", value)
                    .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Idempotency-Key must be a 1-255 character RFC token"));
        }

        verify(messageService, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void create_returnsBadRequestAndDoesNotCallService_whenIdempotencyKeyIsRepeated() throws Exception {
        User user = new User("test@mail.com", "passwordHash");
        ObjectNode body = objectMapper.createObjectNode().put("amount", 4999);
        String payload = objectMapper.writeValueAsString(new MessageCreateDto(UUID.randomUUID(), body));

        mockMvc.perform(
                post("/api/v1/environments/{environmentId}/apps/{appId}/messages", UUID.randomUUID(), UUID.randomUUID())
                        .with(authentication(authFor(user))).header("Idempotency-Key", "first", "second")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Idempotency-Key must be a 1-255 character RFC token"));

        verify(messageService, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void create_returnsConflictWithoutEchoingKeyOrPayload_whenIdempotencyConflictOccurs() throws Exception {
        User user = new User("test@mail.com", "passwordHash");
        UUID envId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        String key = "secret-key-value";
        String payload = "sensitive-payload-value";
        ObjectNode body = objectMapper.createObjectNode().put("amount", payload);
        MessageCreateDto request = new MessageCreateDto(UUID.randomUUID(), body);
        doThrow(new IdempotencyConflictException()).when(messageService).create(
                org.mockito.ArgumentMatchers.eq(request), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(appId), org.mockito.ArgumentMatchers.eq(envId),
                org.mockito.ArgumentMatchers.eq(user.getId()));

        String response = mockMvc
                .perform(post("/api/v1/environments/{environmentId}/apps/{appId}/messages", envId, appId)
                        .with(authentication(authFor(user))).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Idempotency-Key is already associated with a different message request"))
                .andExpect(jsonPath("$.status").value(409)).andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist()).andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(response).doesNotContain(key, payload);
    }
}
