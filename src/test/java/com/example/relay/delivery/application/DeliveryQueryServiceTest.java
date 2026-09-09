package com.example.relay.delivery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.relay.app.domain.App;
import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.delivery.domain.Delivery;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class DeliveryQueryServiceTest {

    @Mock
    private AppRepository appRepository;
    @Mock
    private DeliveryStatusRepository deliveryStatusRepository;
    @Mock
    private DeliveryRepository deliveryRepository;

    private DeliveryQueryService underTest;

    private UUID environmentId;
    private UUID appId;
    private UUID userId;
    private App app;
    private Message message;
    private Endpoint endpoint;

    @BeforeEach
    void setUp() throws Exception {
        underTest = new DeliveryQueryService(appRepository, deliveryStatusRepository, deliveryRepository);

        environmentId = UUID.randomUUID();
        appId = UUID.randomUUID();
        userId = UUID.randomUUID();

        User user = new User("test@mail.com", "hash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        endpoint = new Endpoint("EP 1", "https://example.com/webhook", "whsec_1", app);
        message = new Message(app, event, new ObjectMapper().readTree("{\"amount\":1}"));
    }

    @Test
    void getPage_throwsAppNotFound_whenUserDoesNotOwnTheApp() {
        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(appId, environmentId, userId))
                .thenReturn(Optional.empty());

        assertThrows(AppNotFoundException.class,
                () -> underTest.getPage(appId, environmentId, userId, null, null, null, null, Pageable.unpaged()));
    }

    @Test
    void getPage_delegatesToDeliveryStatusRepository_whenOwnershipChecksOut() {
        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(appId, environmentId, userId))
                .thenReturn(Optional.of(app));
        when(deliveryStatusRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class))).thenReturn(Page.empty());

        Page<?> result = underTest.getPage(appId, environmentId, userId, null, null, null, null,
                Pageable.unpaged());

        assertEquals(0, result.getTotalElements());
    }

    @Test
    void getById_throwsDeliveryNotFound_whenNoMatchingDeliveryExists() {
        UUID deliveryId = UUID.randomUUID();
        when(deliveryRepository.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(deliveryId, appId,
                environmentId, userId)).thenReturn(Optional.empty());

        assertThrows(DeliveryNotFoundException.class,
                () -> underTest.getById(deliveryId, appId, environmentId, userId));
    }

    @Test
    void getById_returnsStatusAndPayload_whenDeliveryExists() {
        Delivery delivery = new Delivery(app, message, endpoint);
        when(deliveryRepository.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(delivery.getId(), appId,
                environmentId, userId)).thenReturn(Optional.of(delivery));
        com.example.relay.delivery.domain.DeliveryStatus status = new com.example.relay.delivery.domain.DeliveryStatus();
        when(deliveryStatusRepository.findById(delivery.getId())).thenReturn(Optional.of(status));

        DeliveryQueryService.DeliveryDetail result =
                underTest.getById(delivery.getId(), appId, environmentId, userId);

        assertEquals(status, result.status());
        assertEquals(message.getBody(), result.payload());
    }
}
