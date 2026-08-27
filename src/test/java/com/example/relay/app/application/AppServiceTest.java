package com.example.relay.app.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.relay.app.api.dto.AppCreateDto;
import com.example.relay.app.domain.App;
import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.app.mapper.AppMapper;
import com.example.relay.environment.domain.Environment;
import com.example.relay.environment.exception.EnvironmentNotFoundException;
import com.example.relay.environment.infrastructure.EnvironmentRepository;
import com.example.relay.user.domain.User;

@ExtendWith(MockitoExtension.class)
public class AppServiceTest {

    @Mock
    private AppRepository appRepository;

    @Mock
    private EnvironmentRepository environmentRepository;

    @Mock
    private AppMapper appMapper;

    @InjectMocks
    private AppService underTest;

    @Test
    void create_savesAppUnderEnvironment_whenEnvironmentBelongsToUser() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        UUID userId = user.getId();

        Environment env = new Environment("Env 1", "Description 1", user);
        UUID envId = env.getId();

        AppCreateDto request = new AppCreateDto("App 1");
        App createdApp = new App(request.name(), env);

        // Stubs
        when(environmentRepository.findByIdAndUserId(envId, userId)).thenReturn(Optional.of(env));
        when(appMapper.toEntity(request, env)).thenReturn(createdApp);
        when(appRepository.save(createdApp)).thenReturn(createdApp);

        // Act
        App result = underTest.create(request, envId, userId);

        // Assert
        assertEquals(request.name(), result.getName());
        assertEquals(envId, result.getEnvironment().getId());
        assertEquals(userId, result.getEnvironment().getUser().getId());
    }

    @Test
    void create_throwsEnvironmentNotFoundException_whenEnvironmentDoesNotExistOrDoesNotBelongToTheAuthenticatedUser() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        UUID userId = user.getId();

        Environment env = new Environment("Env 1", "Description 1", user);
        UUID envId = env.getId();

        AppCreateDto request = new AppCreateDto("App 1");

        // Stubs
        when(environmentRepository.findByIdAndUserId(envId, userId)).thenReturn(Optional.empty());

        // Act + Assert
        assertThrows(
            EnvironmentNotFoundException.class,
            () -> underTest.create(request, envId, userId)
        );

        // Verify
        verify(appMapper, never()).toEntity(any(), any());
        verify(appRepository, never()).save(any());
    }

    @Test
    void getAll_returnsAllAppsUnderEnvironment_whenEnvironmentExistsAndBelongsToUser() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        UUID userId = user.getId();

        Environment env = new Environment("Env 1", "Desc 1", user);
        UUID envId = env.getId();

        List<App> apps = List.of(
            new App("App 1", env),
            new App("App 2", env)
        );

        // Stubs
        when(environmentRepository.findByIdAndUserId(envId, userId)).thenReturn(Optional.of(env));
        when(appRepository.findAllByEnvironmentId(envId)).thenReturn(apps);

        // Act
        List<App> result = underTest.getAll(envId, userId);

        // Assert
        assertEquals(apps.size(), result.size());
        for (int i = 0; i < result.size(); i++) {
            assertEquals(apps.get(i).getId(), result.get(i).getId());
            assertEquals(apps.get(i).getName(), result.get(i).getName());
            assertEquals(apps.get(i).getEnvironment().getId(), result.get(i).getEnvironment().getId());
            assertEquals(apps.get(i).getEnvironment().getUser().getId(), result.get(i).getEnvironment().getUser().getId());
        }
    }

    @Test
    void getById_returnsApp_whenItBelongsToTheUser() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        UUID userId = user.getId();

        Environment env = new Environment("Env 1", "Desc 1", user);

        App app = new App("App 1", env);
        UUID appId = app.getId();

        // Stub
        when(appRepository.findByIdAndEnvironmentUserId(appId, userId)).thenReturn(Optional.of(app));

        // Act
        App result = underTest.getById(appId, userId);

        // Assert
        assertEquals(app.getId(), result.getId());
        assertEquals(app.getEnvironment().getUser().getId(), result.getEnvironment().getUser().getId());
    }

    @Test
    void getById_throwsAppNotFoundException_whenAppDoesNotExistOrDoesNotBelongToUser() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        UUID userId = user.getId();
        User differentUser = new User("diff@mail.com", "someOtherHash");

        Environment env = new Environment("Env 1", "Desc 1", differentUser);

        App app = new App("App 1", env);
        UUID appId = app.getId();

        // Stub
        when(appRepository.findByIdAndEnvironmentUserId(appId, userId)).thenReturn(Optional.empty());

        // Act + Assert
        assertThrows(AppNotFoundException.class, () -> underTest.getById(appId, userId));
    }

    @Test
    void delete_deletesApp_whenItExistsAndBelongsToUser() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        UUID userId = user.getId();

        Environment env = new Environment("Env 1", "Desc 1", user);

        App app = new App("App 1", env);
        UUID appId = app.getId();

        // Stub
        when(appRepository.findByIdAndEnvironmentUserId(appId, userId)).thenReturn(Optional.of(app));

        // Act
        underTest.delete(appId, userId);

        // Verify
        verify(appRepository).delete(app);
    }

    @Test
    void delete_throwsAppNotFoundException_whenAppDoesNotExistOrDoesNotBelongToUser() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        User differentUser = new User("diff@mail.com", "otherPasswordHash");
        UUID userId = user.getId();

        Environment env = new Environment("Env 1", "Desc 1", differentUser);

        App app = new App("App 1", env);
        UUID appId = app.getId();

        // Stub
        when(appRepository.findByIdAndEnvironmentUserId(appId, userId)).thenReturn(Optional.empty());

        // Act + Assert
        assertThrows(AppNotFoundException.class, () -> underTest.delete(appId, userId));

        // Verify
        verify(appRepository, never()).delete(any());
    }
}
