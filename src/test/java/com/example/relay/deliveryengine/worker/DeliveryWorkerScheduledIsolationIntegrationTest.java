package com.example.relay.deliveryengine.worker;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
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
import com.example.relay.support.background.EnableTestBackgroundExecution;
import com.example.relay.support.background.TestBackgroundComponent;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@SpringBootTest
@Testcontainers
@EnableTestBackgroundExecution(TestBackgroundComponent.RABBIT_LISTENERS)
@TestPropertySource(properties = {
        "relay.retry.scheduling-enabled=false",
        "relay.reconciliation.scheduling-enabled=false"
})
class DeliveryWorkerScheduledIsolationIntegrationTest implements SharedPostgresContainer {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4.3.6-management");

    @Autowired
    private AttemptRepository attemptRepository;
    @Autowired
    private DeliveryRepository deliveryRepository;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;
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

    @MockitoSpyBean
    private AttemptService attemptService;

    private MockWebServer mockWebServer;

    @BeforeEach
    void setUp() throws Exception {
        attemptRepository.deleteAll();
        deliveryRepository.deleteAll();
        messageRepository.deleteAll();
        endpointRepository.deleteAll();
        eventRepository.deleteAll();
        appRepository.deleteAll();
        environmentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        userRepository.deleteAll();
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        mockWebServer.shutdown();
    }

    @Test
    void futureScheduledWorkDoesNotExecuteWhenAWorkerReceivesItsTaskMessage() {
        Attempt attempt = persistScheduledAttempt();

        rabbitTemplate.convertAndSend(RabbitMqConfig.DELIVERY_EXCHANGE, RabbitMqConfig.TASKS_ROUTING_KEY,
                attempt.getId().toString());

        verify(attemptService, timeout(5000)).claim(eq(attempt.getId()), any(Instant.class));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertEquals(AttemptStatus.SCHEDULED,
                    attemptRepository.findById(attempt.getId()).orElseThrow().getStatus());
            assertEquals(0, mockWebServer.getRequestCount());
        });
    }

    private Attempt persistScheduledAttempt() {
        User user = userRepository.save(new User("scheduled-" + UUID.randomUUID() + "@mail.com", "hash"));
        Environment environment = environmentRepository.save(new Environment("Env", "Desc", user));
        App app = appRepository.save(new App("App", environment));
        Event event = eventRepository.save(new Event("payment.completed", app));
        Endpoint endpoint = endpointRepository.save(
                new Endpoint("Endpoint", mockWebServer.url("/webhook").toString(), "secret", app));
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 1);
        Message message = messageRepository.save(new Message(app, event, body));
        Delivery delivery = deliveryRepository.save(new Delivery(app, message, endpoint));
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 2);
        attempt.setStatus(AttemptStatus.SCHEDULED);
        attempt.setNextRetryAt(Instant.now().plus(Duration.ofHours(1)));
        return attemptRepository.save(attempt);
    }
}
