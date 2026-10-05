package com.example.relay.delivery.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.relay.app.domain.App;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.domain.DeliveryStatus;
import com.example.relay.delivery.exception.DeliveryNotDeadException;
import com.example.relay.delivery.exception.DeliveryNotFoundException;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.delivery.infrastructure.DeliveryStatusRepository;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.message.domain.Message;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class DeliveryReplayServiceTest {
    @Mock
    DeliveryRepository deliveryRepository;
    @Mock
    DeliveryStatusRepository deliveryStatusRepository;
    @Mock
    AttemptService attemptService;
    @InjectMocks
    DeliveryReplayService underTest;

    private UUID environmentId;
    private UUID appId;
    private UUID userId;
    private Delivery delivery;

    @BeforeEach
    void setUp() {
        User user = new User("test@mail.com", "hash");
        Environment environment = new Environment("Env", "Desc", user);
        App app = new App("App", environment);
        Event event = new Event("payment.completed", app);
        Endpoint endpoint = new Endpoint("EP", "https://example.com/hook", "secret", app);
        Message message = new Message(app, event, new ObjectMapper().createObjectNode());
        delivery = new Delivery(app, message, endpoint);
        environmentId = environment.getId();
        appId = app.getId();
        userId = user.getId();
    }

    @Test
    void replay_rejectsMissingOrUnownedDeliveryBeforeAllocationOrStatusRead() {
        UUID deliveryId = UUID.randomUUID();
        when(deliveryRepository.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(deliveryId, appId,
                environmentId, userId)).thenReturn(Optional.empty());

        assertThrows(DeliveryNotFoundException.class, () -> underTest.replay(deliveryId, appId, environmentId, userId));

        verifyNoInteractions(attemptService, deliveryStatusRepository);
    }

    @Test
    void replay_returnsOneFreshStatusReadAfterAtomicAllocationReturns() {
        authorize();
        DeliveryStatus committedView = new DeliveryStatus();
        AtomicBoolean allocationReturned = new AtomicBoolean();
        when(attemptService.createReplay(delivery)).thenAnswer(invocation -> {
            allocationReturned.set(true);
            return null;
        });
        when(deliveryStatusRepository.findById(delivery.getId())).thenAnswer(invocation -> {
            assertTrue(allocationReturned.get(),
                    "the first view read must occur after the allocation transaction returns");
            return Optional.of(committedView);
        });

        assertSame(committedView, underTest.replay(delivery.getId(), appId, environmentId, userId));

        var ordered = inOrder(deliveryRepository, attemptService, deliveryStatusRepository);
        ordered.verify(deliveryRepository).findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(delivery.getId(),
                appId, environmentId, userId);
        ordered.verify(attemptService).createReplay(delivery);
        ordered.verify(deliveryStatusRepository).findById(delivery.getId());
        verifyNoMoreInteractions(deliveryRepository, attemptService, deliveryStatusRepository);
    }

    @Test
    void replay_propagatesCurrentStateRejectionWithoutReadingStatus() {
        authorize();
        DeliveryNotDeadException rejection = new DeliveryNotDeadException(delivery.getId(), AttemptStatus.SUCCEEDED);
        when(attemptService.createReplay(delivery)).thenThrow(rejection);

        assertSame(rejection, assertThrows(DeliveryNotDeadException.class,
                () -> underTest.replay(delivery.getId(), appId, environmentId, userId)));

        verifyNoInteractions(deliveryStatusRepository);
    }

    @Test
    void replay_propagatesInvariantFailureAsUnexpectedError() {
        authorize();
        DataIntegrityViolationException failure = new DataIntegrityViolationException("sequence invariant violated");
        when(attemptService.createReplay(delivery)).thenThrow(failure);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> underTest.replay(delivery.getId(), appId, environmentId, userId)));

        verifyNoInteractions(deliveryStatusRepository);
    }

    @Test
    void replay_rejectsMissingPostCommitView() {
        authorize();
        when(deliveryStatusRepository.findById(delivery.getId())).thenReturn(Optional.empty());

        assertThrows(DeliveryNotFoundException.class,
                () -> underTest.replay(delivery.getId(), appId, environmentId, userId));

        verify(attemptService).createReplay(delivery);
    }

    private void authorize() {
        when(deliveryRepository.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(delivery.getId(), appId,
                environmentId, userId)).thenReturn(Optional.of(delivery));
    }
}
