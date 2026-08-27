package com.example.relay.environment.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
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

import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.common.security.CustomUserDetailsService;
import com.example.relay.common.security.JwtService;
import com.example.relay.common.security.SecurityConfig;
import com.example.relay.environment.api.dto.EnvironmentCreateDto;
import com.example.relay.environment.api.dto.EnvironmentResponseDto;
import com.example.relay.environment.api.dto.EnvironmentUpdateDto;
import com.example.relay.environment.application.EnvironmentService;
import com.example.relay.environment.domain.Environment;
import com.example.relay.environment.exception.EnvironmentNotFoundException;
import com.example.relay.environment.mapper.EnvironmentMapper;
import com.example.relay.user.application.AuthService;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(EnvironmentController.class)
@Import(SecurityConfig.class)
public class EnvironmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EnvironmentService environmentService;

    @MockitoBean
    private EnvironmentMapper environmentMapper;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void create_createsAndReturnsEnvironment_whenUserIsAuthorizedAndRequestBodyIsCorrect() throws Exception {
        // Arrange
        EnvironmentCreateDto requestBody = new EnvironmentCreateDto("Env 1", "Desc 1");
        UUID userId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(userId, "dakshkant8@gmail.com");
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        User user = new User("dakshkant8@gmail.com", "passwordHash");

        Environment createdEnvironment = new Environment(requestBody.name(), requestBody.description(), user);
        when(environmentService.create(requestBody, userId)).thenReturn(createdEnvironment);

        EnvironmentResponseDto response = new EnvironmentResponseDto(
            createdEnvironment.getId(),
            createdEnvironment.getName(),
            createdEnvironment.getDescription(),
            createdEnvironment.getUpdatedAt()
        );
        when(environmentMapper.toResponseDto(createdEnvironment)).thenReturn(response);

        // Act + Assert
        mockMvc.perform(
            post("/api/v1/environments")
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(requestBody))
        )
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(response.id().toString()))
        .andExpect(jsonPath("$.name").value(response.name()))
        .andExpect(jsonPath("$.description").value(response.description()))
        .andExpect(jsonPath("$.updatedAt").value(response.updatedAt().toString()));
        
        // Verify
        verify(environmentService).create(requestBody, userId);
        verify(environmentMapper).toResponseDto(createdEnvironment);
    }

    @Test
    void getById_returnsUserEnvironment_ifItExists() throws Exception {
        // Arrange
        UUID userId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(userId, "dakshkant8@gmail.com");
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        User user = new User("dakshkant8@gmail.com", "passwordHash");

        Environment env = new Environment("Env 1", "Desc 1", user);
        UUID envId = env.getId();

        EnvironmentResponseDto response = new EnvironmentResponseDto(
            env.getId(),
            env.getName(),
            env.getDescription(),
            env.getUpdatedAt()
        );

        when(environmentService.getById(envId, userId)).thenReturn(env);
        when(environmentMapper.toResponseDto(env)).thenReturn(response);

        String apiPath = "/api/v1/environments/%s".formatted(envId.toString());
        
        // Act + Assert
        mockMvc.perform(
            get(apiPath)
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(response.id().toString()))
        .andExpect(jsonPath("$.name").value(response.name()))
        .andExpect(jsonPath("$.description").value(response.description()))
        .andExpect(jsonPath("$.updatedAt").value(response.updatedAt().toString()));

        // Verify
        verify(environmentService).getById(envId, userId);
        verify(environmentMapper).toResponseDto(env);
    }

    @Test
    void getAll_returnsAllEnvironmentsOfUser_whenUserIsAuthorized() throws Exception {
        // Arrange
        UUID userId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(userId, "dakshkant8@gmail.com");
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        User user = new User("dakshkant8@gmail.com", "passwordHash");

        Environment env1 = new Environment(
            "Env 1",
            "Desc 1",
            user
        );
        Environment env2 = new Environment(
            "Env 2",
            "Desc 2",
            user
        );

        List<Environment> envs = List.of(env1, env2);

        List<EnvironmentResponseDto> response = new ArrayList<>();
        response.add(
            new EnvironmentResponseDto(
                env1.getId(),
                env1.getName(),
                env1.getDescription(),
                env1.getUpdatedAt()
            )
        );
        response.add(
            new EnvironmentResponseDto(
                env2.getId(),
                env2.getName(),
                env2.getDescription(),
                env2.getUpdatedAt()
            )
        );

        when(environmentService.getAll(userId)).thenReturn(envs);
        when(environmentMapper.toResponseDtoList(envs)).thenReturn(response);

        // Act + Assert
        mockMvc.perform(
            get("/api/v1/environments")
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk());

        // Verify
        verify(environmentService).getAll(userId);
        verify(environmentMapper).toResponseDtoList(envs);
    }

    @Test
    void getById_returns404_whenEnvironmentNotFound() throws Exception {
        // Arrange
        UUID userId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(userId, "dakshkant8@gmail.com");
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        UUID missingId = UUID.randomUUID();

        when(environmentService.getById(missingId, userId)).thenThrow(new EnvironmentNotFoundException(missingId));

        // Act + Assert
        mockMvc.perform(
            get("/api/v1/environments/{id}", missingId)
            .with(authentication(auth))
        )
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Environment not found with id: " + missingId));

        // Verify
        verify(environmentMapper, never()).toResponseDto(any());
    }

    @Test
    void partialUpdate_updatesDescriptionAndReturnsEnvironment_whenValid() throws Exception {
        // Arrange
        UUID userId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(userId, "dakshkant8@gmail.com");
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        User user = new User("dakshkant8@gmail.com", "passwordHash");

        EnvironmentUpdateDto requestBody = new EnvironmentUpdateDto("Updated description");
        Environment environment = new Environment("Env 1", "Updated description", user);
        when(environmentService.updateDescription(environment.getId(), requestBody.description(), userId))
            .thenReturn(environment);

        EnvironmentResponseDto response = new EnvironmentResponseDto(
            environment.getId(),
            environment.getName(),
            environment.getDescription(),
            environment.getUpdatedAt()
        );
        when(environmentMapper.toResponseDto(environment)).thenReturn(response);

        // Act + Assert
        mockMvc.perform(
            patch("/api/v1/environments/{id}", environment.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(requestBody))
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.description").value("Updated description"));

        // Verify
        verify(environmentService).updateDescription(environment.getId(), requestBody.description(), userId);
    }

    @Test
    void partialUpdate_returns400_whenDescriptionExceedsMaxLength() throws Exception {
        // Arrange
        UUID userId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(userId, "dakshkant8@gmail.com");
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        UUID environmentId = UUID.randomUUID();

        EnvironmentUpdateDto requestBody = new EnvironmentUpdateDto("a".repeat(501));

        // Act + Assert
        mockMvc.perform(
            patch("/api/v1/environments/{id}", environmentId)
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(requestBody))
        )
        .andExpect(status().isBadRequest());

        // Verify
        verify(environmentService, never()).updateDescription(any(), any(), any());
    }

    @Test
    void delete_deletesEnvironment_andReturnsNoContent() throws Exception {
        // Arrange
        UUID userId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(userId, "dakshkant8@gmail.com");
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        UUID environmentId = UUID.randomUUID();

        // Act + Assert
        mockMvc.perform(
            delete("/api/v1/environments/{id}", environmentId)
            .with(authentication(auth))
        )
        .andExpect(status().isNoContent());

        // Verify
        verify(environmentService).delete(environmentId, userId);
    }
}
