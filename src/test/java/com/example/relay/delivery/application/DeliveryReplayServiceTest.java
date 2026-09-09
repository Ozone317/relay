package com.example.relay.delivery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.relay.app.domain.App;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.domain.DeliveryStatus;
import com.example.relay.delivery.exception.ActiveAttemptAlreadyExistsException;
import com.example.relay.delivery.exception.DeliveryNotDeadException;
import com.example.relay.delivery.exception.DeliveryNotFoundException;
import com.example.relay.delivery.exception.ReplayEndpointInactiveException;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.delivery.infrastructure.DeliveryStatusRepository;
import com.example.relay.deliveryengine.publisher.AttemptPublisher;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.message.domain.Message;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class DeliveryReplayServiceTest {

    @Mock
    private DeliveryRepository deliveryRepository;
    @Mock
    private DeliveryStatusRepository deliveryStatusRepository;
    @Mock
    private AttemptRepository attemptRepository;
    @Mock
    private AttemptService attemptService;
    @Mock
    private AttemptPublisher attemptPublisher;
    @Mock
    private EntityManager entityManager;

    private DeliveryReplayService underTest;

    private UUID environmentId;
    private UUID appId;
    private UUID userId;
    private Delivery delivery;
    private Attempt deadAttempt;

    @BeforeEach
    void setUp() throws Exception {
        underTest = new DeliveryReplayService(deliveryRepository, deliveryStatusRepository, attemptRepository,
                attemptService, attemptPublisher, entityManager);

        environmentId = UUID.randomUUID();
        appId = UUID.randomUUID();
        userId = UUID.randomUUID();

        User user = new User("test@mail.com", "hash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        Endpoint endpoint = new Endpoint("EP 1", "https://example.com/webhook", "whsec_1", app);
        Message message = new Message(app, event, new ObjectMapper().readTree("{\"amount\":1}"));
        delivery = new Delivery(app, message, endpoint);
        deadAttempt = new Attempt(app, message, endpoint, delivery, 6);
        deadAttempt.setStatus(AttemptStatus.DEAD);
    }

    private DeliveryStatus statusOf(AttemptStatus status) throws Exception {
        DeliveryStatus deliveryStatus = new DeliveryStatus();
        set(deliveryStatus, "deliveryId", delivery.getId());
        set(deliveryStatus, "status", status);
        set(deliveryStatus, "latestAttemptId", deadAttempt.getId());
        return deliveryStatus;
    }

    private void set(Object target, String field, Object value) throws Exception {
        Field f = DeliveryStatus.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void replay_throwsDeliveryNotFound_whenNoMatchingDeliveryExists() {
        UUID deliveryId = UUID.randomUUID();
        when(deliveryRepository.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(deliveryId, appId,
                environmentId, userId)).thenReturn(Optional.empty());

        assertThrows(DeliveryNotFoundException.class,
                () -> underTest.replay(deliveryId, appId, environmentId, userId));
    }

    @Test
    void replay_throwsDeliveryNotDead_whenLatestAttemptIsNotDead() throws Exception {
        when(deliveryRepository.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(delivery.getId(), appId,
                environmentId, userId)).thenReturn(Optional.of(delivery));
        when(deliveryStatusRepository.findById(delivery.getId()))
                .thenReturn(Optional.of(statusOf(AttemptStatus.SUCCEEDED)));

        assertThrows(DeliveryNotDeadException.class,
                () -> underTest.replay(delivery.getId(), appId, environmentId, userId));
    }

    @Test
    void replay_throwsReplayEndpointInactive_whenEndpointIsInactive() throws Exception {
        delivery.getEndpoint().setActive(false);
        when(deliveryRepository.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(delivery.getId(), appId,
                environmentId, userId)).thenReturn(Optional.of(delivery));
        when(deliveryStatusRepository.findById(delivery.getId()))
                .thenReturn(Optional.of(statusOf(AttemptStatus.DEAD)));

        assertThrows(ReplayEndpointInactiveException.class,
                () -> underTest.replay(delivery.getId(), appId, environmentId, userId));
    }

    @Test
    void replay_throwsActiveAttemptAlreadyExists_whenAnActiveRowAlreadyExistsForThePair() throws Exception {
        when(deliveryRepository.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(delivery.getId(), appId,
                environmentId, userId)).thenReturn(Optional.of(delivery));
        when(deliveryStatusRepository.findById(delivery.getId()))
                .thenReturn(Optional.of(statusOf(AttemptStatus.DEAD)));
        when(attemptRepository.existsByMessageIdAndEndpointIdAndStatusIn(delivery.getMessage().getId(),
                delivery.getEndpoint().getId(),
                List.of(AttemptStatus.CREATED, AttemptStatus.IN_FLIGHT, AttemptStatus.SCHEDULED)))
                        .thenReturn(true);

        assertThrows(ActiveAttemptAlreadyExistsException.class,
                () -> underTest.replay(delivery.getId(), appId, environmentId, userId));

        verify(attemptService, never()).createReplay(any());
    }

    @Test
    // NOTE: this test cannot, by construction, catch the open-in-view stale-read bug that
    // motivated DeliveryReplayService.replay()'s entityManager.detach(current) call - stubbing two
    // consecutive findById calls to return different objects encodes the *intended* post-fix
    // behaviour as if it were automatically true. It is kept as documentation of intent only; the
    // real regression test is DeliveryReplayHttpIntegrationTest, which goes through the actual HTTP
    // layer (and therefore the real OSIV-bound Hibernate session) where this bug was only
    // observable in the first place.
    void replay_createsAndPublishesTheReplay_whenEligible() throws Exception {
        Attempt replayAttempt = new Attempt(delivery.getApp(), delivery.getMessage(), delivery.getEndpoint(),
                delivery, 7);
        DeliveryStatus updatedStatus = statusOf(AttemptStatus.CREATED);

        when(deliveryRepository.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(delivery.getId(), appId,
                environmentId, userId)).thenReturn(Optional.of(delivery));
        when(deliveryStatusRepository.findById(delivery.getId()))
                .thenReturn(Optional.of(statusOf(AttemptStatus.DEAD)))
                .thenReturn(Optional.of(updatedStatus));
        when(attemptRepository.existsByMessageIdAndEndpointIdAndStatusIn(any(), any(), any())).thenReturn(false);
        when(attemptRepository.findById(deadAttempt.getId())).thenReturn(Optional.of(deadAttempt));
        when(attemptService.createReplay(deadAttempt)).thenReturn(replayAttempt);

        DeliveryStatus result = underTest.replay(delivery.getId(), appId, environmentId, userId);

        assertEquals(updatedStatus, result);
        verify(attemptPublisher).publish(replayAttempt.getId());
    }

    @Test
    void replay_throwsActiveAttemptAlreadyExists_whenCreateReplayLosesTheInsertRace() throws Exception {
        // The existsByMessageIdAndEndpointIdAndStatusIn check above is only a fast path;
        // idx_attempts_one_active_per_message_endpoint is the real authority. A concurrent replay
        // for the same (message, endpoint) pair can win the check-then-insert race and still fail
        // at createReplay's insert - that must surface as the same conflict the fast path reports.
        when(deliveryRepository.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(delivery.getId(), appId,
                environmentId, userId)).thenReturn(Optional.of(delivery));
        when(deliveryStatusRepository.findById(delivery.getId()))
                .thenReturn(Optional.of(statusOf(AttemptStatus.DEAD)));
        when(attemptRepository.existsByMessageIdAndEndpointIdAndStatusIn(any(), any(), any())).thenReturn(false);
        when(attemptRepository.findById(deadAttempt.getId())).thenReturn(Optional.of(deadAttempt));
        when(attemptService.createReplay(deadAttempt)).thenThrow(new DataIntegrityViolationException("lost race"));

        assertThrows(ActiveAttemptAlreadyExistsException.class,
                () -> underTest.replay(delivery.getId(), appId, environmentId, userId));

        verify(attemptPublisher, never()).publish(any());
    }
}
