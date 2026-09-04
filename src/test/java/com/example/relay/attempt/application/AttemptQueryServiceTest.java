package com.example.relay.attempt.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.example.relay.app.domain.App;
import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.exception.AttemptNotFoundException;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.message.domain.Message;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
public class AttemptQueryServiceTest {

    @Mock
    private AppRepository appRepository;

    @Mock
    private AttemptRepository attemptRepository;

    @InjectMocks
    private AttemptQueryService underTest;

    @Test
    void getPage_returnsPage_whenAppBelongsToEnvironmentAndUser() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_test", app);
        Message message = new Message(app, event, new ObjectMapper().readTree("{}"));
        Attempt attempt = new Attempt(app, message, endpoint, 1);
        Pageable pageable = PageRequest.of(0, 10);
        Page<Attempt> page = new PageImpl<>(List.of(attempt));

        // Stubs
        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId()))
                .thenReturn(Optional.of(app));
        when(attemptRepository.findByAppIdAndFilters(app.getId(), null, null, null, null, pageable))
                .thenReturn(page);

        // Act
        Page<Attempt> result =
                underTest.getPage(app.getId(), env.getId(), user.getId(), null, null, null, null, pageable);

        // Assert
        assertEquals(1, result.getTotalElements());
        assertEquals(attempt.getId(), result.getContent().get(0).getId());
    }

    @Test
    void getPage_passesFiltersThrough_whenProvided() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_test", app);
        Pageable pageable = PageRequest.of(0, 10);
        Instant from = Instant.now().minusSeconds(3600);
        Instant to = Instant.now();

        // Stub
        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId()))
                .thenReturn(Optional.of(app));
        when(attemptRepository.findByAppIdAndFilters(app.getId(), endpoint.getId(), AttemptStatus.DEAD, from, to,
                pageable)).thenReturn(Page.empty());

        // Act
        underTest.getPage(app.getId(), env.getId(), user.getId(), endpoint.getId(), AttemptStatus.DEAD, from, to,
                pageable);

        // Verify
        verify(attemptRepository).findByAppIdAndFilters(app.getId(), endpoint.getId(), AttemptStatus.DEAD, from, to,
                pageable);
    }

    @Test
    void getPage_throwsAppNotFoundException_whenAppDoesNotExistOrDoesNotBelongToUser() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Pageable pageable = PageRequest.of(0, 10);

        // Stub
        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId()))
                .thenReturn(Optional.empty());

        // Act + Assert
        assertThrows(AppNotFoundException.class,
                () -> underTest.getPage(app.getId(), env.getId(), user.getId(), null, null, null, null, pageable));

        // Verify
        verify(attemptRepository, never()).findByAppIdAndFilters(any(), any(), any(), any(), any(), any());
    }

    @Test
    void getById_returnsAttempt_whenItBelongsToTheAppEnvironmentAndUser() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_test", app);
        Message message = new Message(app, event, new ObjectMapper().readTree("{}"));
        Attempt attempt = new Attempt(app, message, endpoint, 1);

        // Stub
        when(attemptRepository.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(attempt.getId(), app.getId(),
                env.getId(), user.getId())).thenReturn(Optional.of(attempt));

        // Act
        Attempt result = underTest.getById(attempt.getId(), app.getId(), env.getId(), user.getId());

        // Assert
        assertEquals(attempt.getId(), result.getId());
    }

    @Test
    void getById_throwsAttemptNotFoundException_whenAttemptDoesNotExistOrDoesNotMatchAppEnvironmentOrUser() {
        // Arrange
        UUID attemptId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID envId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // Stub
        when(attemptRepository.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(attemptId, appId, envId,
                userId)).thenReturn(Optional.empty());

        // Act + Assert
        assertThrows(AttemptNotFoundException.class, () -> underTest.getById(attemptId, appId, envId, userId));
    }
}
