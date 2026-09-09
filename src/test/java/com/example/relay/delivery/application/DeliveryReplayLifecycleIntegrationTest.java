package com.example.relay.delivery.application;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.domain.DeliveryStatus;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.deliveryengine.config.RabbitMqConfig;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.endpoint.infrastructure.EndpointRepository;
import com.example.relay.environment.domain.Environment;
import com.example.relay.environment.infrastructure.EnvironmentRepository;
import com.example.relay.event.domain.Event;
import com.example.relay.event.infrastructure.EventRepository;
import com.example.relay.message.domain.Message;
import com.example.relay.message.infrastructure.MessageRepository;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-stack replay lifecycle through the real worker pipeline (publish -> worker consumes -> HTTP
 * call via MockWebServer -> status transition), ported from the pre-Task-8
 * AttemptReplayLifecycleIntegrationTest onto DeliveryReplayService's delivery-scoped API
 * (replay(UUID deliveryId, ...): DeliveryStatus instead of replay(UUID attemptId, ...): Attempt)
 * after AttemptReplayService was deleted as dead code. Delivery is write-once, so a delivery replayed
 * twice is still replay(delivery.getId(), ...) both times - the same Delivery carried forward, never
 * a new one.
 */
@SpringBootTest
@Testcontainers
public class DeliveryReplayLifecycleIntegrationTest implements SharedPostgresContainer {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4-management");

    @Autowired
    private DeliveryReplayService deliveryReplayService;

    @Autowired
    private AttemptRepository attemptRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private AppRepository appRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private MessageRepository messageRepository;

    private MockWebServer mockWebServer;

    private UUID environmentId;
    private UUID appId;
    private UUID userId;
    private App app;
    private Event event;
    private Message message;

    @BeforeEach
    void setUp() throws IOException {
        clearDatabase();
        drainQueues();
        rabbitListenerEndpointRegistry.getListenerContainer("deadLetterNotifier").stop();

        mockWebServer = new MockWebServer();
        mockWebServer.start();

        User user = userRepository.save(new User("replay-" + UUID.randomUUID() + "@mail.com", "hash"));
        Environment env = environmentRepository.save(new Environment("Env 1", "Desc 1", user));
        app = appRepository.save(new App("App 1", env));
        event = eventRepository.save(new Event("payment.completed", app));
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        message = messageRepository.save(new Message(app, event, body));

        environmentId = env.getId();
        appId = app.getId();
        userId = user.getId();
    }

    @AfterEach
    void tearDown() throws IOException {
        clearDatabase();
        rabbitListenerEndpointRegistry.getListenerContainer("deadLetterNotifier").start();
        mockWebServer.shutdown();
    }

    private void drainQueues() {
        while (rabbitTemplate.receive(RabbitMqConfig.WAIT_30S_QUEUE, 100) != null) {
            // discard leftover messages from a prior test
        }
        while (rabbitTemplate.receive(RabbitMqConfig.DEADLETTER_QUEUE, 100) != null) {
            // discard leftover messages from a prior test
        }
    }

    private void clearDatabase() {
        attemptRepository.deleteAll();
        deliveryRepository.deleteAll();
        messageRepository.deleteAll();
        endpointRepository.deleteAll();
        eventRepository.deleteAll();
        appRepository.deleteAll();
        environmentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    private Delivery persistDeadDelivery(String url) {
        Endpoint endpoint = endpointRepository.save(new Endpoint("EP 1", url, "whsec_1", app));
        Delivery delivery = deliveryRepository.save(new Delivery(app, message, endpoint));
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 6);
        attempt.setStatus(AttemptStatus.DEAD);
        attemptRepository.save(attempt);
        return delivery;
    }

    @Test
    void replaySucceeds_marksTheNewAttemptSucceeded() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        Delivery delivery = persistDeadDelivery(mockWebServer.url("/webhook").toString());
        Attempt dead = attemptRepository.findByDeliveryId(delivery.getId(),
                org.springframework.data.domain.Pageable.unpaged()).getContent().get(0);

        DeliveryStatus result = deliveryReplayService.replay(delivery.getId(), appId, environmentId, userId);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(result.getLatestAttemptId()).orElseThrow();
            assertEquals(AttemptStatus.SUCCEEDED, reloaded.getStatus());
            assertEquals(dead.getAttemptNo() + 1, reloaded.getAttemptNo());
        });
    }

    @Test
    void replayFails_goesStraightBackToDead_notFailedRetryingOrScheduled() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("still broken"));
        Delivery delivery = persistDeadDelivery(mockWebServer.url("/webhook").toString());

        DeliveryStatus result = deliveryReplayService.replay(delivery.getId(), appId, environmentId, userId);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(result.getLatestAttemptId()).orElseThrow();
            assertEquals(AttemptStatus.DEAD, reloaded.getStatus());
        });

        // Proves this reached DEAD via handleFailure's isFinal branch, not via a wait-tier requeue.
        assertNull(rabbitTemplate.receive(RabbitMqConfig.WAIT_30S_QUEUE, 2000));
    }

    @Test
    void aFailedReplay_canBeReplayedAgain_attemptNoKeepsIncrementing() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("still broken"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        Delivery delivery = persistDeadDelivery(mockWebServer.url("/webhook").toString());
        Attempt dead = attemptRepository.findByDeliveryId(delivery.getId(),
                org.springframework.data.domain.Pageable.unpaged()).getContent().get(0);

        DeliveryStatus firstReplay = deliveryReplayService.replay(delivery.getId(), appId, environmentId, userId);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(AttemptStatus.DEAD,
                attemptRepository.findById(firstReplay.getLatestAttemptId()).orElseThrow().getStatus()));

        // Delivery is write-once - a second replay of the same delivery, not a new one.
        DeliveryStatus secondReplay = deliveryReplayService.replay(delivery.getId(), appId, environmentId, userId);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(secondReplay.getLatestAttemptId()).orElseThrow();
            assertEquals(AttemptStatus.SUCCEEDED, reloaded.getStatus());
            assertEquals(dead.getAttemptNo() + 2, reloaded.getAttemptNo());
        });
    }
}
