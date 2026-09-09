package com.example.relay.attempt.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.relay.app.domain.App;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.exception.ActiveAttemptAlreadyExistsException;
import com.example.relay.attempt.exception.AttemptNotDeadException;
import com.example.relay.attempt.exception.AttemptNotFoundException;
import com.example.relay.attempt.exception.ReplayEndpointInactiveException;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.deliveryengine.publisher.AttemptPublisher;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.message.domain.Message;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AttemptReplayServiceTest {

    @Mock
    private AttemptRepository attemptRepository;
    @Mock
    private AttemptService attemptService;
    @Mock
    private AttemptPublisher attemptPublisher;

    private AttemptReplayService underTest;

    private UUID environmentId;
    private UUID appId;
    private UUID userId;
    private Attempt deadAttempt;

    @BeforeEach
    void setUp() throws Exception {
        underTest = new AttemptReplayService(attemptRepository, attemptService, attemptPublisher);

        environmentId = UUID.randomUUID();
        appId = UUID.randomUUID();
        userId = UUID.randomUUID();

        User user = new User("test@mail.com", "hash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        Endpoint endpoint = new Endpoint("EP 1", "https://example.com/webhook", "whsec_1", app);
        Message message = new Message(app, event, new ObjectMapper().readTree("{\"amount\":1}"));
        deadAttempt = new Attempt(app, message, endpoint, 6);
        deadAttempt.setStatus(AttemptStatus.DEAD);
    }

    @Test
    void replay_throwsAttemptNotFound_whenNoMatchingAttemptExists() {
        UUID attemptId = UUID.randomUUID();
        when(attemptRepository.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(attemptId, appId,
                environmentId, userId)).thenReturn(Optional.empty());

        assertThrows(AttemptNotFoundException.class,
                () -> underTest.replay(attemptId, appId, environmentId, userId));
    }

    @Test
    void replay_throwsAttemptNotDead_whenStatusIsNotDead() {
        deadAttempt.setStatus(AttemptStatus.SUCCEEDED);
        when(attemptRepository.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(deadAttempt.getId(),
                appId, environmentId, userId)).thenReturn(Optional.of(deadAttempt));

        assertThrows(AttemptNotDeadException.class,
                () -> underTest.replay(deadAttempt.getId(), appId, environmentId, userId));
    }

    @Test
    void replay_throwsReplayEndpointInactive_whenEndpointIsInactive() {
        deadAttempt.getEndpoint().setActive(false);
        when(attemptRepository.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(deadAttempt.getId(),
                appId, environmentId, userId)).thenReturn(Optional.of(deadAttempt));

        assertThrows(ReplayEndpointInactiveException.class,
                () -> underTest.replay(deadAttempt.getId(), appId, environmentId, userId));
    }

    @Test
    void replay_throwsActiveAttemptAlreadyExists_whenAnActiveRowAlreadyExistsForThePair() {
        when(attemptRepository.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(deadAttempt.getId(),
                appId, environmentId, userId)).thenReturn(Optional.of(deadAttempt));
        when(attemptRepository.existsByMessageIdAndEndpointIdAndStatusIn(deadAttempt.getMessage().getId(),
                deadAttempt.getEndpoint().getId(),
                List.of(AttemptStatus.CREATED, AttemptStatus.IN_FLIGHT, AttemptStatus.SCHEDULED)))
                        .thenReturn(true);

        assertThrows(ActiveAttemptAlreadyExistsException.class,
                () -> underTest.replay(deadAttempt.getId(), appId, environmentId, userId));

        verify(attemptService, never()).createReplay(any());
    }

    @Test
    void replay_createsAndPublishesTheReplay_whenEligible() {
        Attempt replayAttempt = new Attempt(deadAttempt.getApp(), deadAttempt.getMessage(),
                deadAttempt.getEndpoint(), 7);

        when(attemptRepository.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(deadAttempt.getId(),
                appId, environmentId, userId)).thenReturn(Optional.of(deadAttempt));
        when(attemptRepository.existsByMessageIdAndEndpointIdAndStatusIn(any(), any(), any())).thenReturn(false);
        when(attemptService.createReplay(deadAttempt)).thenReturn(replayAttempt);

        // Act
        Attempt result = underTest.replay(deadAttempt.getId(), appId, environmentId, userId);

        // Assert
        assertEquals(replayAttempt, result);
        verify(attemptPublisher).publish(replayAttempt.getId());
    }
}
