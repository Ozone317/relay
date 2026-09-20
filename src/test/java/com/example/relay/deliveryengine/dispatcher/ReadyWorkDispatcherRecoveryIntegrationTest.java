package com.example.relay.deliveryengine.dispatcher;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.attempt.infrastructure.ReadyWorkRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.deliveryengine.config.RabbitMqConfig;
import com.example.relay.deliveryengine.reconciliation.ReconciliationSweeper;
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
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.task.scheduling.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "relay.retry.scheduling-enabled=false",
        "relay.reconciliation.scheduling-enabled=false",
        "relay.reconciliation.batch-size=10",
        "relay.reconciliation.interval=1h",
        "relay.reconciliation.dead-letter-grace=1h",
        "relay.retry.scheduler-interval=1h",
        "relay.retry.dispatcher-interval=1h"
})
class ReadyWorkDispatcherRecoveryIntegrationTest implements SharedPostgresContainer {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4.3.6-management");

    @Autowired
    private ReconciliationSweeper sweeper;
    @Autowired
    private ReadyWorkDispatcher dispatcher;
    @Autowired
    private ReadyWorkRepository readyWorkRepository;
    @Autowired
    private AttemptService attemptService;
    @Autowired
    private AttemptRepository attemptRepository;
    @Autowired
    private DeliveryRepository deliveryRepository;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
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
        emailVerificationTokenRepository.deleteAll();
        userRepository.deleteAll();
        while (rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 100) != null) {
            // discard leftover tasks from a prior test
        }
    }

    @Test
    void recoveredInFlightAttemptIsRepublishedOnlyByDispatcher() {
        Attempt attempt = persistCreatedAttempt();
        assertEquals(true, attemptService.claim(attempt.getId(), Instant.now()));
        backdateAttempt(attempt.getId());

        sweeper.sweep();

        Attempt recovered = attemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(AttemptStatus.CREATED, recovered.getStatus());
        assertNull(recovered.getReadyPublishedAt());
        assertNull(rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 1000),
                "reconciliation must not publish recovered work directly");

        dispatcher.dispatchOnce();

        org.springframework.amqp.core.Message task = rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 5000);
        assertNotNull(task);
        assertEquals(attempt.getId().toString(), new String(task.getBody()));
    }

    @Test
    void dispatcherLeaseBlocksImmediateRecoveryButAllowsRecoveryAfterGrace() {
        Attempt attempt = persistCreatedAttempt();
        UUID claimId = UUID.randomUUID();
        assertEquals(java.util.List.of(attempt.getId()),
                readyWorkRepository.claimUnpublishedReady(claimId, Duration.ofSeconds(10), 1));

        dispatcher.dispatchOnce();
        assertNull(rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 1000),
                "a live dispatcher lease must block duplicate publication");

        backdateReadyLease(attempt.getId());
        dispatcher.dispatchOnce();

        org.springframework.amqp.core.Message task = rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 5000);
        assertNotNull(task);
        assertEquals(attempt.getId().toString(), new String(task.getBody()));
    }

    private Attempt persistCreatedAttempt() {
        User user = userRepository.save(new User("recovery-" + UUID.randomUUID() + "@mail.com", "hash"));
        Environment environment = environmentRepository.save(new Environment("Env", "Desc", user));
        App app = appRepository.save(new App("App", environment));
        Event event = eventRepository.save(new Event("payment.completed", app));
        Endpoint endpoint = endpointRepository.save(new Endpoint("Endpoint", "https://example.com/webhook", "secret", app));
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 1);
        Message message = messageRepository.save(new Message(app, event, body));
        Delivery delivery = deliveryRepository.save(new Delivery(app, message, endpoint));
        return attemptRepository.save(new Attempt(app, message, endpoint, delivery, 1));
    }

    private void backdateAttempt(UUID attemptId) {
        jdbcTemplate.update("UPDATE attempts SET updated_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE id = ?",
                attemptId);
    }

    private void backdateReadyLease(UUID attemptId) {
        jdbcTemplate.update(
                "UPDATE attempts SET ready_dispatch_claimed_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = ?",
                attemptId);
    }
}
