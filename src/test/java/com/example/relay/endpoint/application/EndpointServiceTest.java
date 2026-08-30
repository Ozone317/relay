package com.example.relay.endpoint.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.relay.app.domain.App;
import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.endpoint.api.dto.EndpointCreateDto;
import com.example.relay.endpoint.api.dto.EndpointUpdateDto;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.endpoint.exception.EndpointAlreadyExistsException;
import com.example.relay.endpoint.exception.EndpointNotFoundException;
import com.example.relay.endpoint.infrastructure.EndpointRepository;
import com.example.relay.endpoint.mapper.EndpointMapper;
import com.example.relay.endpoint.utils.SigningSecretGenerator;
import com.example.relay.environment.domain.Environment;
import com.example.relay.user.domain.User;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
public class EndpointServiceTest {

    @Mock
    private AppRepository appRepository;

    @Mock
    private EndpointMapper endpointMapper;

    @Mock
    private EndpointRepository endpointRepository;

    @Mock
    private SigningSecretGenerator signingSecretGenerator;

    @InjectMocks
    private EndpointService underTest;

    @Test
    void create_savesEndpointUnderApp_whenAppBelongsToUserAndTheGivenEnvironment() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        EndpointCreateDto request = new EndpointCreateDto("Production", "https://example.com/webhook");
        Endpoint endpoint = new Endpoint(request.name(), request.url(), "whsec_generated", app);

        // Stubs
        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId()))
                .thenReturn(Optional.of(app));
        when(endpointRepository.findByNameAndAppIdAndEnvironmentIdAndUserId(request.name(), app.getId(), env.getId(),
                user.getId())).thenReturn(Optional.empty());
        when(signingSecretGenerator.generate()).thenReturn("whsec_generated");
        when(endpointMapper.toEntity(request, app, "whsec_generated")).thenReturn(endpoint);
        when(endpointRepository.saveAndFlush(endpoint)).thenReturn(endpoint);

        // Act
        Endpoint result = underTest.create(request, app.getId(), env.getId(), user.getId());

        // Assert
        assertEquals(request.name(), result.getName());
        assertEquals(request.url(), result.getUrl());
        assertEquals("whsec_generated", result.getSigningSecret());
        assertEquals(app.getId(), result.getApp().getId());
    }

    @Test
    void create_throwsAppNotFoundException_whenAppDoesNotExistOrDoesNotBelongToUser() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        EndpointCreateDto request = new EndpointCreateDto("Production", "https://example.com/webhook");

        // Stub
        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId()))
                .thenReturn(Optional.empty());

        // Act + Assert
        assertThrows(AppNotFoundException.class,
                () -> underTest.create(request, app.getId(), env.getId(), user.getId()));

        // Verify
        verify(endpointMapper, never()).toEntity(any(), any(), any());
        verify(endpointRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_throwsEndpointAlreadyExistsException_whenEndpointWithSameNameAlreadyExists() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        EndpointCreateDto request = new EndpointCreateDto("Production", "https://example.com/webhook");
        Endpoint existing = new Endpoint(request.name(), request.url(), "whsec_existing", app);

        // Stub
        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId()))
                .thenReturn(Optional.of(app));
        when(endpointRepository.findByNameAndAppIdAndEnvironmentIdAndUserId(request.name(), app.getId(), env.getId(),
                user.getId())).thenReturn(Optional.of(existing));

        // Act + Assert
        assertThrows(EndpointAlreadyExistsException.class,
                () -> underTest.create(request, app.getId(), env.getId(), user.getId()));

        // Verify
        verify(endpointRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_throwsEndpointAlreadyExistsException_whenTwoRequestsRaceToCreateEndpointWithSameName() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        EndpointCreateDto request = new EndpointCreateDto("Production", "https://example.com/webhook");
        Endpoint endpoint = new Endpoint(request.name(), request.url(), "whsec_generated", app);

        // Stub: the pre-check passes (no duplicate found yet)...
        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId()))
                .thenReturn(Optional.of(app));
        when(endpointRepository.findByNameAndAppIdAndEnvironmentIdAndUserId(request.name(), app.getId(), env.getId(),
                user.getId())).thenReturn(Optional.empty());
        when(signingSecretGenerator.generate()).thenReturn("whsec_generated");
        when(endpointMapper.toEntity(request, app, "whsec_generated")).thenReturn(endpoint);
        // ...but the save itself hits the DB unique constraint, simulating a concurrent request winning
        // the race.
        doThrow(new DataIntegrityViolationException(null)).when(endpointRepository).saveAndFlush(endpoint);

        // Act + Assert
        assertThrows(EndpointAlreadyExistsException.class,
                () -> underTest.create(request, app.getId(), env.getId(), user.getId()));
    }

    @Test
    void getById_returnsEndpoint_whenItBelongsToTheAppEnvironmentAndUser() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_test", app);

        // Stub
        when(endpointRepository.findByIdAndAppIdAndEnvironmentIdAndUserId(endpoint.getId(), app.getId(), env.getId(),
                user.getId())).thenReturn(Optional.of(endpoint));

        // Act
        Endpoint result = underTest.getById(endpoint.getId(), app.getId(), env.getId(), user.getId());

        // Assert
        assertEquals(endpoint.getId(), result.getId());
    }

    @Test
    void getById_throwsEndpointNotFoundException_whenEndpointDoesNotExistOrDoesNotMatchAppEnvironmentOrUser() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_test", app);

        // Stub
        when(endpointRepository.findByIdAndAppIdAndEnvironmentIdAndUserId(endpoint.getId(), app.getId(), env.getId(),
                user.getId())).thenReturn(Optional.empty());

        // Act + Assert
        assertThrows(EndpointNotFoundException.class,
                () -> underTest.getById(endpoint.getId(), app.getId(), env.getId(), user.getId()));
    }

    @Test
    void getAll_returnsAllEndpoints_whenTheyBelongToTheAppEnvironmentAndUser() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        List<Endpoint> endpoints = List.of(new Endpoint("Production", "https://example.com/webhook", "whsec_1", app),
                new Endpoint("Staging", "https://staging.example.com/webhook", "whsec_2", app));

        // Stub
        when(endpointRepository.findAllByAppIdAndEnvironmentIdAndUserId(app.getId(), env.getId(), user.getId()))
                .thenReturn(endpoints);

        // Act
        List<Endpoint> result = underTest.getAll(app.getId(), env.getId(), user.getId());

        // Assert
        assertEquals(endpoints.size(), result.size());
        assertEquals(endpoints.get(0).getId(), result.get(0).getId());
        assertEquals(endpoints.get(1).getId(), result.get(1).getId());
    }

    @Test
    void update_updatesNameUrlAndActive_whenAllFieldsProvided() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Original", "https://example.com/webhook", "whsec_test", app);
        EndpointUpdateDto request = new EndpointUpdateDto("Updated", "https://updated.example.com/webhook", false);

        // Stub
        when(endpointRepository.findByIdAndAppIdAndEnvironmentIdAndUserId(endpoint.getId(), app.getId(), env.getId(),
                user.getId())).thenReturn(Optional.of(endpoint));

        // Act
        Endpoint result = underTest.update(request, endpoint.getId(), app.getId(), env.getId(), user.getId());

        // Assert
        assertEquals("Updated", result.getName());
        assertEquals("https://updated.example.com/webhook", result.getUrl());
        assertEquals(false, result.isActive());

        // Verify
        verify(endpointRepository).save(endpoint);
    }

    @Test
    void update_onlyUpdatesActive_whenNameAndUrlAreNotProvided() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Original", "https://example.com/webhook", "whsec_test", app);
        EndpointUpdateDto request = new EndpointUpdateDto(null, null, false);

        // Stub
        when(endpointRepository.findByIdAndAppIdAndEnvironmentIdAndUserId(endpoint.getId(), app.getId(), env.getId(),
                user.getId())).thenReturn(Optional.of(endpoint));

        // Act
        Endpoint result = underTest.update(request, endpoint.getId(), app.getId(), env.getId(), user.getId());

        // Assert
        assertEquals("Original", result.getName());
        assertEquals("https://example.com/webhook", result.getUrl());
        assertEquals(false, result.isActive());
    }

    @Test
    void update_throwsEndpointNotFoundException_whenEndpointDoesNotExistOrDoesNotMatchAppEnvironmentOrUser() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Original", "https://example.com/webhook", "whsec_test", app);
        EndpointUpdateDto request = new EndpointUpdateDto("Updated", null, null);

        // Stub
        when(endpointRepository.findByIdAndAppIdAndEnvironmentIdAndUserId(endpoint.getId(), app.getId(), env.getId(),
                user.getId())).thenReturn(Optional.empty());

        // Act + Assert
        assertThrows(EndpointNotFoundException.class,
                () -> underTest.update(request, endpoint.getId(), app.getId(), env.getId(), user.getId()));

        // Verify
        verify(endpointRepository, never()).save(any());
    }

    @Test
    void delete_deletesEndpoint_whenItExistsAndBelongsToTheAppEnvironmentAndUser() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_test", app);

        // Stub
        when(endpointRepository.findByIdAndAppIdAndEnvironmentIdAndUserId(endpoint.getId(), app.getId(), env.getId(),
                user.getId())).thenReturn(Optional.of(endpoint));

        // Act
        underTest.delete(endpoint.getId(), app.getId(), env.getId(), user.getId());

        // Verify
        verify(endpointRepository).delete(endpoint);
    }

    @Test
    void delete_throwsEndpointNotFoundException_whenEndpointDoesNotExistOrDoesNotMatchAppEnvironmentOrUser() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_test", app);

        // Stub
        when(endpointRepository.findByIdAndAppIdAndEnvironmentIdAndUserId(endpoint.getId(), app.getId(), env.getId(),
                user.getId())).thenReturn(Optional.empty());

        // Act + Assert
        assertThrows(EndpointNotFoundException.class,
                () -> underTest.delete(endpoint.getId(), app.getId(), env.getId(), user.getId()));

        // Verify
        verify(endpointRepository, never()).delete(any());
    }
}
