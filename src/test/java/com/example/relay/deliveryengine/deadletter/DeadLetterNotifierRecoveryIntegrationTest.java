package com.example.relay.deliveryengine.deadletter;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.deliveryengine.config.RabbitMqConfig;
import com.example.relay.email.EmailSendException;
import com.example.relay.email.EmailSendResult;
import com.example.relay.email.EmailService;
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

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {"relay.reconciliation.interval=2s", "relay.reconciliation.created-grace=2s",
        "relay.reconciliation.dead-letter-grace=2s"})
class DeadLetterNotifierRecoveryIntegrationTest implements SharedPostgresContainer {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4-management");

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
    private EnvironmentRepository environmentRepository;

    @Autowired
    private AppRepository appRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private MessageRepository messageRepository;

    @MockitoBean
    private EmailService emailService;

    private Endpoint endpoint;
    private Message message;

    @BeforeEach
    void setUp() {
        attemptRepository.deleteAll();
        deliveryRepository.deleteAll();
        messageRepository.deleteAll();
        endpointRepository.deleteAll();
        eventRepository.deleteAll();
        appRepository.deleteAll();
        environmentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        User user = userRepository.save(new User("test" + UUID.randomUUID() + "@mail.com", "hash"));
        Environment env = environmentRepository.save(new Environment("Env 1", "Desc 1", user));
        App app = appRepository.save(new App("App 1", env));
        Event event = eventRepository.save(new Event("payment.completed", app));
        endpoint = endpointRepository.save(new Endpoint("EP 1", "https://example.com/webhook", "whsec_1", app));
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        message = messageRepository.save(new Message(app, event, body));
    }

    @Test
    void sendFailsOnce_thenRecoverDeadLetterSweepRetries_andEventuallyClaims() {
        Delivery delivery = deliveryRepository.save(new Delivery(endpoint.getApp(), message, endpoint));
        Attempt attempt = new Attempt(endpoint.getApp(), message, endpoint, delivery, 6);
        attempt.setStatus(AttemptStatus.DEAD);
        attempt = attemptRepository.save(attempt);

        AtomicInteger callCount = new AtomicInteger(0);
        when(emailService.send(any(), anyMap(), anyString(), anyString())).thenAnswer(invocation -> {
            if (callCount.getAndIncrement() == 0) {
                throw new EmailSendException("simulated Brevo outage on first attempt");
            }
            return EmailSendResult.SENT;
        });

        rabbitTemplate.convertAndSend(RabbitMqConfig.DELIVERY_EXCHANGE, RabbitMqConfig.DEADLETTER_ROUTING_KEY,
                attempt.getId().toString());

        // The first delivery fails and is dropped (see DeadLetterNotifierIntegrationTest for why);
        // recoverDeadLetter() - running on the shortened interval/grace above - republishes the row
        // once it's stale, the second delivery succeeds, and the row is finally claimed.
        UUID attemptId = attempt.getId();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attemptId).orElseThrow();
            assertNotNull(reloaded.getDeadLetterNotifiedAt());
        });
    }
}
