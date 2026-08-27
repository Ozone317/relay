package com.example.relay.app.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

import com.example.relay.app.api.dto.AppCreateDto;
import com.example.relay.app.api.dto.AppResponseDto;
import com.example.relay.app.application.AppService;
import com.example.relay.app.domain.App;
import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.app.mapper.AppMapper;
import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.common.security.CustomUserDetailsService;
import com.example.relay.common.security.JwtService;
import com.example.relay.common.security.SecurityConfig;
import com.example.relay.environment.domain.Environment;
import com.example.relay.user.application.AuthService;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(AppController.class)
@Import(SecurityConfig.class)
public class AppControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AppService appService;

    @MockitoBean
    private AppMapper appMapper;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void create_createsAndReturnsApp_ifItBelongsToTheEnvironmentAndUser() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        AppCreateDto request = new AppCreateDto("App 1");
        App createdApp = new App("App 1", env);
        AppResponseDto response = new AppResponseDto(
            createdApp.getId(),
            createdApp.getName(),
            createdApp.getEnvironment().getId(),
            createdApp.getCreatedAt()
        );
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        // Stubs
        when(appService.create(request, env.getId(), user.getId())).thenReturn(createdApp);
        when(appMapper.toResponseDto(createdApp)).thenReturn(response);

        // Act
        mockMvc.perform(
            post("/api/v1/environments/{environmentId}/apps", env.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(response.id().toString()))
        .andExpect(jsonPath("$.name").value(response.name()))
        .andExpect(jsonPath("$.environmentId").value(response.environmentId().toString()))
        .andExpect(jsonPath("$.createdAt").value(response.createdAt().toString()));

        // Verify
        verify(appService).create(request, env.getId(), user.getId());
        verify(appMapper).toResponseDto(createdApp);
    }

    @Test
    void getById_returnsApp_whenAppIsCreatedByUserAndEnvironmentIsCreatedByUserAndAppBelongsToTheEnvironment() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        AppResponseDto response = new AppResponseDto(
            app.getId(),
            app.getName(),
            app.getEnvironment().getId(),
            app.getCreatedAt()
        );

        // Stub
        when(appService.getById(app.getId(), env.getId(), user.getId())).thenReturn(app);
        when(appMapper.toResponseDto(app)).thenReturn(response);

        // Act
        mockMvc.perform(
            get("/api/v1/environments/{environmentId}/apps/{appId}", env.getId(), app.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(response.id().toString()))
        .andExpect(jsonPath("$.name").value(response.name()))
        .andExpect(jsonPath("$.environmentId").value(response.environmentId().toString()))
        .andExpect(jsonPath("$.createdAt").value(response.createdAt().toString()));

        // Verify
        verify(appService).getById(app.getId(), env.getId(), user.getId());
        verify(appMapper).toResponseDto(app);
    }

    @Test
    void getById_returns404_whenAppIsNotFound() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        User differentUser = new User("diff@mail.com", "someOtherHash");

        Environment env = new Environment("Env 1", "Desc 1", differentUser);
        App app = new App("App 1", env);
        
        // Stub
        when(appService.getById(app.getId(), env.getId(), user.getId())).thenThrow(new AppNotFoundException(app.getId()));

        // Act + Assert
        mockMvc.perform(
            get("/api/v1/environments/{environmentId}/apps/{appId}", env.getId(), app.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("App not found with id: " + app.getId()));

        // Verify
        verify(appMapper,never()).toResponseDto(any());
    }

    @Test
    void getAll_returnsAllApps_whenTheyBelongToTheEnvironmentAndCreatedByUser() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        Environment env = new Environment("Env 1", "Desc 1", user);
        App app1 = new App("App 1", env);
        App app2 = new App("App 2", env);
        App app3 = new App("App 3", env);

        List<App> apps = List.of(app1, app2, app3);

        List<AppResponseDto> response = List.of(
            new AppResponseDto(
                app1.getId(),
                app1.getName(),
                app1.getEnvironment().getId(),
                app1.getCreatedAt()
            ),
            new AppResponseDto(
                app2.getId(),
                app2.getName(),
                app2.getEnvironment().getId(),
                app2.getCreatedAt()
            ),
            new AppResponseDto(
                app3.getId(),
                app3.getName(),
                app3.getEnvironment().getId(),
                app3.getCreatedAt()
            )
        );

        // Stubs
        when(appService.getAll(env.getId(), user.getId())).thenReturn(apps);
        when(appMapper.toResponseDtoList(apps)).thenReturn(response);

        // Act
        mockMvc.perform(
            get("/api/v1/environments/{environmentId}/apps", env.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(response.get(0).id().toString()))
        .andExpect(jsonPath("$[0].name").value(response.get(0).name()))
        .andExpect(jsonPath("$[0].environmentId").value(response.get(0).environmentId().toString()))
        .andExpect(jsonPath("$[0].createdAt").value(response.get(0).createdAt().toString()))
        .andExpect(jsonPath("$[1].id").value(response.get(1).id().toString()))
        .andExpect(jsonPath("$[1].name").value(response.get(1).name()))
        .andExpect(jsonPath("$[1].environmentId").value(response.get(1).environmentId().toString()))
        .andExpect(jsonPath("$[1].createdAt").value(response.get(1).createdAt().toString()))
        .andExpect(jsonPath("$[2].id").value(response.get(2).id().toString()))
        .andExpect(jsonPath("$[2].name").value(response.get(2).name()))
        .andExpect(jsonPath("$[2].environmentId").value(response.get(2).environmentId().toString()))
        .andExpect(jsonPath("$[2].createdAt").value(response.get(2).createdAt().toString()));

        // Verify
        verify(appService).getAll(env.getId(), user.getId());
        verify(appMapper).toResponseDtoList(apps);
    }

    @Test
    void getAll_returnsEmptyList_whenNoAppBelongsToTheUsersEnvironment() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        Environment env = new Environment("Env 1", "Desc 1", user);

        // Stubs
        when(appService.getAll(env.getId(), user.getId())).thenReturn(List.of());
        when(appMapper.toResponseDtoList(List.of())).thenReturn(List.of());

        // Act
        mockMvc.perform(
            get("/api/v1/environments/{environmentId}/apps", env.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));

        // Verify
        verify(appService).getAll(env.getId(), user.getId());
        verify(appMapper).toResponseDtoList(List.of());
    }

    @Test
    void delete_deletesTheApp_whenItExistsInTheEnvironmentAndIsCreatedByTheUser() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        
        // Act + Assert
        mockMvc.perform(
            delete("/api/v1/environments/{environmentId}/apps/{appId}", env.getId(), app.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isNoContent());

        // Verify
        verify(appService).delete(app.getId(), env.getId(), user.getId());
    }

    @Test
    void delete_throwsAppNotFoundException_whenAppDoesNotBelongToTheEnvironmentOrUser() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        User differentUser = new User("diff@mail.com", "someOtherHash");
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        Environment env = new Environment("Env 1", "Desc 1", differentUser);
        App app = new App("App 1", env);

        // Stub
        doThrow(new AppNotFoundException(app.getId()))
        .when(appService).delete(app.getId(), env.getId(), user.getId());

        // Act + Assert
        mockMvc.perform(
            delete("/api/v1/environments/{environmentId}/apps/{appId}", env.getId(), app.getId())
            .with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("App not found with id: " + app.getId()));

        // Verify
        verify(appService).delete(app.getId(), env.getId(), user.getId());
    }
}
