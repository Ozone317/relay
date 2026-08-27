package com.example.relay.event.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.api.dto.EventCreateDto;
import com.example.relay.event.api.dto.EventResponseDto;
import com.example.relay.event.application.EventService;
import com.example.relay.event.domain.Event;
import com.example.relay.event.exception.EventAlreadyExistsException;
import com.example.relay.event.mapper.EventMapper;
import com.example.relay.user.application.AuthService;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(EventController.class)
@Import(SecurityConfig.class)
public class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EventService eventService;

    @MockitoBean
    private EventMapper eventMapper;

        @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void create_createsAndReturnsEvent_ifItBelongsToTheAppAndUser() throws Exception{
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        EventCreateDto request = new EventCreateDto("payment.created");
        Event event = new Event("payment.created", app);
        EventResponseDto response = new EventResponseDto(event.getId(), event.getName(), app.getId(), event.getCreatedAt(), 0);
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        // Stubs
        when(eventService.create(request, app.getId(), user.getId())).thenReturn(event);
        when(eventMapper.toResponseDto(event)).thenReturn(response);

        // Act
        mockMvc.perform(
            post("/api/v1/environments/{environmentId}/apps/{appId}/events", env.getId(), app.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(response.id().toString()))
        .andExpect(jsonPath("$.name").value(response.name()))
        .andExpect(jsonPath("$.appId").value(response.appId().toString()))
        .andExpect(jsonPath("$.createdAt").value(response.createdAt().toString()));

        // Verify
        verify(eventService).create(request, app.getId(), user.getId());
        verify(eventMapper).toResponseDto(event);
    }

    @Test
    void create_returnsConflict_whenEventWithSameNameAlreadyExists() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        EventCreateDto request = new EventCreateDto("payment.created");

        // Stubs
        doThrow(new EventAlreadyExistsException(request.name())).when(eventService).create(request, app.getId(), user.getId());

        // Act
        mockMvc.perform(
            post("/api/v1/environments/{environmentId}/apps/{appId}/events", env.getId(), app.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("Event already exists with name: " + request.name()));

        // Verify
        verify(eventMapper, never()).toResponseDto(any());
    }

    @Test
    void getAll_returnsAllEvents_whenTheyBelongToTheEnvironmentAndCreatedByUser() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        List<Event> events = List.of(
            new Event("payment.created", app),
            new Event("user.created", app)
        );
        List<EventResponseDto> response = List.of(
            new EventResponseDto(events.get(0).getId(), events.get(0).getName(), app.getId(), events.get(0).getCreatedAt(), 0),
            new EventResponseDto(events.get(1).getId(), events.get(1).getName(), app.getId(), events.get(1).getCreatedAt(), 0)
        );
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Stubs
        when(eventService.getAll(app.getId(), user.getId())).thenReturn(events);
        when(eventMapper.toResponseDtoList(events)).thenReturn(response);

        // Act
        mockMvc.perform(
            get("/api/v1/environments/{environmentId}/apps/{appId}/events", env.getId(), app.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(response.get(0).id().toString()))
        .andExpect(jsonPath("$[0].name").value(response.get(0).name()))
        .andExpect(jsonPath("$[0].appId").value(response.get(0).appId().toString()))
        .andExpect(jsonPath("$[0].createdAt").value(response.get(0).createdAt().toString()))
        .andExpect(jsonPath("$[1].id").value(response.get(1).id().toString()))
        .andExpect(jsonPath("$[1].name").value(response.get(1).name()))
        .andExpect(jsonPath("$[1].appId").value(response.get(1).appId().toString()))
        .andExpect(jsonPath("$[1].createdAt").value(response.get(1).createdAt().toString()));

        // Verify
        verify(eventService).getAll(app.getId(), user.getId());
        verify(eventMapper).toResponseDtoList(events);
    }

    @Test
    void getAll_returnsEmptyList_whenNoEventBelongsToTheUsersApp() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        User differentUser = new User("diff@mail.com", "otherHash");
        Environment env = new Environment("Env 1", "Desc 1", differentUser);
        App app = new App("App 1", env);
        
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Stubs
        when(eventService.getAll(app.getId(), user.getId())).thenReturn(List.of());
        when(eventMapper.toResponseDtoList(List.of())).thenReturn(List.of());

        // Act
        mockMvc.perform(
            get("/api/v1/environments/{environmentId}/apps/{appId}/events", env.getId(), app.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));

        // Verify
        verify(eventService).getAll(app.getId(), user.getId());
        verify(eventMapper).toResponseDtoList(List.of());
    }
}
