package com.example.relay.deliveryengine.deadletter;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Tag("integration")
@SpringBootTest
@Testcontainers
class DeadLetterNotifierIntegrationTest implements SharedPostgresContainer {

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

    @MockitoBean
    private EmailService emailService;

    private Endpoint endpoint;
    private Message message;
    private User user;

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

        reset(emailService);
        when(emailService.send(any(), anyMap(), anyString(), anyString())).thenReturn(EmailSendResult.SENT);

        user = userRepository.save(new User("test" + UUID.randomUUID() + "@mail.com", "hash"));
        Environment env = environmentRepository.save(new Environment("Env 1", "Desc 1", user));
        App app = appRepository.save(new App("App 1", env));
        Event event = eventRepository.save(new Event("payment.completed", app));
        endpoint = endpointRepository.save(new Endpoint("EP 1", "https://example.com/webhook", "whsec_1", app));
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        message = messageRepository.save(new Message(app, event, body));
    }

    private Attempt persistDeadAttempt() {
        Delivery delivery = deliveryRepository.save(new Delivery(endpoint.getApp(), message, endpoint));
        Attempt attempt = new Attempt(endpoint.getApp(), message, endpoint, delivery, 6);
        attempt.setStatus(AttemptStatus.DEAD);
        return attemptRepository.save(attempt);
    }

    @Test
    void deadLetterMessage_sendsEmail_thenSetsNotifiedAt() {
        Attempt attempt = persistDeadAttempt();

        rabbitTemplate.convertAndSend(RabbitMqConfig.DELIVERY_EXCHANGE, RabbitMqConfig.DEADLETTER_ROUTING_KEY,
                attempt.getId().toString());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertNotNull(reloaded.getDeadLetterNotifiedAt());
        });

        verify(emailService, times(1)).send(eq(com.example.relay.email.EmailTemplate.DEAD_LETTER_NOTIFICATION),
                anyMap(), eq(user.getEmail()), eq(attempt.getId().toString()));
    }

    @Test
    void redeliveredDeadLetterMessage_doesNotSendEmailTwice() throws InterruptedException {
        Attempt attempt = persistDeadAttempt();

        // Simulate RabbitMQ at-least-once redelivery: two messages for the same attempt. This proves
        // DB-level (read-side) dedup only - by the time the second message is consumed, the first has
        // already claimed the row, so the early deadLetterNotifiedAt check short-circuits it. Genuine
        // provider-level idempotency (both consumers racing before either claims) is proven separately
        // in DeadLetterNotifierTest (the DUPLICATE-still-claims unit test) and BrevoEmailSenderTest
        // (the duplicate_parameter case) - see spec Section 6.1/Section 8 for why this distinction
        // matters and isn't just re-testing the same thing twice.
        rabbitTemplate.convertAndSend(RabbitMqConfig.DELIVERY_EXCHANGE, RabbitMqConfig.DEADLETTER_ROUTING_KEY,
                attempt.getId().toString());
        rabbitTemplate.convertAndSend(RabbitMqConfig.DELIVERY_EXCHANGE, RabbitMqConfig.DEADLETTER_ROUTING_KEY,
                attempt.getId().toString());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertNotNull(reloaded.getDeadLetterNotifiedAt());
        });

        Thread.sleep(2000);
        verify(emailService, times(1)).send(any(), anyMap(), any(), any());
    }

    @Test
    void sendFailure_doesNotClaimTheNotification() {
        // Regression test for send-then-claim ordering (spec Section 6). Verified (not assumed):
        // this listener has no explicit AcknowledgeMode, so it runs under Spring AMQP's default AUTO
        // mode; default-requeue-rejected=false is set project-wide; delivery.deadletter's queue
        // definition has no dead-letter-exchange argument. So the thrown exception here causes the
        // message to be rejected and simply dropped from the queue - not requeued, not redirected.
        // That's fine because recovery is entirely DB-state-driven (recoverDeadLetter() checks
        // dead_letter_notified_at IS NULL, independent of what happened to the queue message) - see
        // DeadLetterNotifierRecoveryIntegrationTest for the end-to-end proof of that recovery path.
        // Sabotage this test by temporarily reverting DeadLetterNotifier to claim-then-send and
        // confirm it then fails, before trusting it as a real regression test.
        when(emailService.send(any(), anyMap(), anyString(), anyString()))
                .thenThrow(new EmailSendException("simulated Brevo outage"));

        Attempt attempt = persistDeadAttempt();

        rabbitTemplate.convertAndSend(RabbitMqConfig.DELIVERY_EXCHANGE, RabbitMqConfig.DEADLETTER_ROUTING_KEY,
                attempt.getId().toString());

        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertNull(reloaded.getDeadLetterNotifiedAt());
        });
    }
}
