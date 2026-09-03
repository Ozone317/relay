package com.example.relay.delivery.deadletter;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.config.RabbitMqConfig;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.endpoint.infrastructure.EndpointRepository;
import com.example.relay.environment.domain.Environment;
import com.example.relay.environment.infrastructure.EnvironmentRepository;
import com.example.relay.event.domain.Event;
import com.example.relay.event.infrastructure.EventRepository;
import com.example.relay.message.domain.Message;
import com.example.relay.message.infrastructure.MessageRepository;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@SpringBootTest
@Testcontainers
class DeadLetterNotifierIntegrationTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4-management");

    @Autowired
    private AttemptRepository attemptRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private UserRepository userRepository;

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

    private Endpoint endpoint;
    private Message message;

    @BeforeEach
    void setUp() {
        attemptRepository.deleteAll();
        messageRepository.deleteAll();
        endpointRepository.deleteAll();
        eventRepository.deleteAll();
        appRepository.deleteAll();
        environmentRepository.deleteAll();
        userRepository.deleteAll();

        User user = userRepository.save(new User("test" + UUID.randomUUID() + "@mail.com", "hash"));
        Environment env = environmentRepository.save(new Environment("Env 1", "Desc 1", user));
        App app = appRepository.save(new App("App 1", env));
        Event event = eventRepository.save(new Event("payment.completed", app));
        endpoint = endpointRepository.save(new Endpoint("EP 1", "https://example.com/webhook", "whsec_1", app));
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        message = messageRepository.save(new Message(app, event, body));
    }

    private Attempt persistDeadAttempt() {
        Attempt attempt = new Attempt(endpoint.getApp(), message, endpoint, 6);
        attempt.setStatus(AttemptStatus.DEAD);
        return attemptRepository.save(attempt);
    }

    @Test
    void deadLetterMessage_setsNotifiedAtAndLogsStubEmail() {
        Attempt attempt = persistDeadAttempt();

        Logger notifierLogger = (Logger) LoggerFactory.getLogger(DeadLetterNotifier.class);
        AtomicInteger stubEmailCount = new AtomicInteger(0);
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (event.getFormattedMessage().contains("[STUB EMAIL]")) {
                    stubEmailCount.incrementAndGet();
                }
            }
        };
        appender.start();
        notifierLogger.addAppender(appender);

        try {
            rabbitTemplate.convertAndSend(RabbitMqConfig.DELIVERY_EXCHANGE, RabbitMqConfig.DEADLETTER_ROUTING_KEY,
                    attempt.getId().toString());

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
                assertNotNull(reloaded.getDeadLetterNotifiedAt());
            });
            assertEquals(1, stubEmailCount.get());
        } finally {
            notifierLogger.detachAppender(appender);
        }
    }

    @Test
    void redeliveredDeadLetterMessage_doesNotNotifyTwice() throws InterruptedException {
        Attempt attempt = persistDeadAttempt();

        Logger notifierLogger = (Logger) LoggerFactory.getLogger(DeadLetterNotifier.class);
        AtomicInteger stubEmailCount = new AtomicInteger(0);
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (event.getFormattedMessage().contains("[STUB EMAIL]")) {
                    stubEmailCount.incrementAndGet();
                }
            }
        };
        appender.start();
        notifierLogger.addAppender(appender);

        try {
            // Simulate RabbitMQ at-least-once redelivery: two messages for the same attempt.
            rabbitTemplate.convertAndSend(RabbitMqConfig.DELIVERY_EXCHANGE, RabbitMqConfig.DEADLETTER_ROUTING_KEY,
                    attempt.getId().toString());
            rabbitTemplate.convertAndSend(RabbitMqConfig.DELIVERY_EXCHANGE, RabbitMqConfig.DEADLETTER_ROUTING_KEY,
                    attempt.getId().toString());

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
                assertNotNull(reloaded.getDeadLetterNotifiedAt());
            });

            // Give the second (redundant) message time to be consumed and skipped.
            Thread.sleep(2000);
            assertEquals(1, stubEmailCount.get(),
                    "expected exactly one notification even though the attempt was delivered twice");
        } finally {
            notifierLogger.detachAppender(appender);
        }
    }
}
