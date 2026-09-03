package com.example.relay.delivery.worker;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.config.RabbitMqConfig;
import com.example.relay.delivery.publisher.AttemptPublisher;
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
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
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

@SpringBootTest
@Testcontainers
public class DeliveryWorkerIntegrationTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4-management");

    @Autowired
    private AttemptPublisher attemptPublisher;

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

    private MockWebServer mockWebServer;

    @BeforeEach
    void setUp() throws IOException {
        clearDatabase();
        drainQueues();

        mockWebServer = new MockWebServer();
        mockWebServer.start();
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
        messageRepository.deleteAll();
        endpointRepository.deleteAll();
        eventRepository.deleteAll();
        appRepository.deleteAll();
        environmentRepository.deleteAll();
        userRepository.deleteAll();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    private Attempt persistAttempt(String url, int attemptNo) {
        User user = userRepository.save(new User("test" + UUID.randomUUID().toString() + "@mail.com", "passwordHash"));
        Environment env = environmentRepository.save(new Environment("Env 1", "Desc 1", user));
        App app = appRepository.save(new App("App 1", env));
        Event event = eventRepository.save(new Event("payment.completed", app));
        Endpoint endpoint = endpointRepository.save(new Endpoint("EP 1", url, "whsec_1", app));
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        Message message = messageRepository.save(new Message(app, event, body));

        return attemptRepository.save(new Attempt(app, message, endpoint, attemptNo));
    }

    @Test
    void successfulDelivery_marksAttemptSucceeded() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        Attempt attempt = persistAttempt(mockWebServer.url("/webhook").toString(), 1);

        attemptPublisher.publish(attempt.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(AttemptStatus.SUCCEEDED, reloaded.getStatus());
            assertEquals(200, reloaded.getResponseCode());
            assertEquals("ok", reloaded.getResponseBody());
        });
    }

    @Test
    void non2xxResponse_beforeFinalAttempt_marksAttemptFailedAndCreatesRetry() {
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(500)
                        .setBody("internal error"));

        Attempt attempt = persistAttempt(
                mockWebServer.url("/webhook").toString(),
                1);

        Instant before = Instant.now();

        attemptPublisher.publish(attempt.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt original = attemptRepository
                    .findById(attempt.getId())
                    .orElseThrow();

            assertEquals(
                    AttemptStatus.FAILED_RETRYING,
                    original.getStatus());

            assertEquals(500, original.getResponseCode());
            assertEquals("internal error", original.getResponseBody());
            assertNull(original.getLastError());
            assertNotNull(original.getLatencyMs());
            assertNotNull(original.getNextRetryAt());

            RetryTier tier = RetryTier.forAttemptNo(
                    attempt.getAttemptNo() + 1);

            Instant expected = before.plus(tier.getDelay());

            assertTrue(
                    !original.getNextRetryAt().isBefore(expected.minusSeconds(1)),
                    "nextAttemptAt should respect the retry delay");
        });

        AtomicReference<Attempt> retryHolder = new AtomicReference<>();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<Attempt> attempts = attemptRepository.findAll();

            assertEquals(2, attempts.size());

            Attempt retry = attempts.stream()
                    .filter(a -> !a.getId().equals(attempt.getId()))
                    .findFirst()
                    .orElseThrow();

            assertEquals(2, retry.getAttemptNo());
            assertEquals(
                    attempt.getApp().getId(),
                    retry.getApp().getId());
            assertEquals(
                    attempt.getMessage().getId(),
                    retry.getMessage().getId());
            assertEquals(
                    attempt.getEndpoint().getId(),
                    retry.getEndpoint().getId());
            assertEquals(AttemptStatus.SCHEDULED, retry.getStatus());
            assertNotNull(retry.getNextRetryAt());

            retryHolder.set(retry);
        });

        org.springframework.amqp.core.Message queued =
                rabbitTemplate.receive(RabbitMqConfig.WAIT_30S_QUEUE, 5000);
        assertNotNull(queued, "expected the retry attempt to be published to " + RabbitMqConfig.WAIT_30S_QUEUE);
        assertEquals(retryHolder.get().getId().toString(), new String(queued.getBody()));
    }

    @Test
    void exception_beforeFinalAttempt_marksAttemptFailedAndCreatesRetry() throws IOException {
        int port = mockWebServer.getPort();

        mockWebServer.shutdown();

        Attempt attempt = persistAttempt(
                "http://localhost:" + port + "/webhook",
                1);

        attemptPublisher.publish(attempt.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt original = attemptRepository
                    .findById(attempt.getId())
                    .orElseThrow();

            assertEquals(
                    AttemptStatus.FAILED_RETRYING,
                    original.getStatus());

            assertNull(original.getResponseCode());
            assertNull(original.getResponseBody());

            assertNotNull(original.getLastError());
            assertNotNull(original.getLatencyMs());
            assertNotNull(original.getNextRetryAt());
        });

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<Attempt> attempts = attemptRepository.findAll();

            assertEquals(2, attempts.size());

            Attempt retry = attempts.stream()
                    .filter(a -> !a.getId().equals(attempt.getId()))
                    .findFirst()
                    .orElseThrow();

            assertEquals(2, retry.getAttemptNo());
            assertEquals(
                    attempt.getApp().getId(),
                    retry.getApp().getId());
            assertEquals(
                    attempt.getMessage().getId(),
                    retry.getMessage().getId());
            assertEquals(
                    attempt.getEndpoint().getId(),
                    retry.getEndpoint().getId());
            assertEquals(AttemptStatus.SCHEDULED, retry.getStatus());
            assertNotNull(retry.getNextRetryAt());
        });
    }

    @Test
    void non2xxResponse_onFinalAttempt_marksAttemptDead() {
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(500)
                        .setBody("internal error"));

        Attempt attempt = persistAttempt(
                mockWebServer.url("/webhook").toString(),
                RetryTier.MAX_ATTEMPTS);

        attemptPublisher.publish(attempt.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository
                    .findById(attempt.getId())
                    .orElseThrow();

            assertEquals(AttemptStatus.DEAD, reloaded.getStatus());
            assertEquals(500, reloaded.getResponseCode());
            assertEquals("internal error", reloaded.getResponseBody());
            assertNull(reloaded.getLastError());
            assertNotNull(reloaded.getLatencyMs());
        });

        assertEquals(1, attemptRepository.findAll().size());
        assertEquals(1, mockWebServer.getRequestCount());

        org.springframework.amqp.core.Message deadLettered =
                rabbitTemplate.receive(RabbitMqConfig.DEADLETTER_QUEUE, 5000);
        assertNotNull(deadLettered, "expected the attempt id on " + RabbitMqConfig.DEADLETTER_QUEUE);
        assertEquals(attempt.getId().toString(), new String(deadLettered.getBody()));
    }

    @Test
    void exception_onFinalAttempt_marksAttemptDead() throws IOException {

        int port = mockWebServer.getPort();

        mockWebServer.shutdown();

        Attempt attempt = persistAttempt(
                "http://localhost:" + port + "/webhook",
                RetryTier.MAX_ATTEMPTS);

        attemptPublisher.publish(attempt.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository
                    .findById(attempt.getId())
                    .orElseThrow();

            assertEquals(AttemptStatus.DEAD, reloaded.getStatus());

            assertNull(reloaded.getResponseCode());
            assertNull(reloaded.getResponseBody());

            assertNotNull(reloaded.getLastError());
            assertNotNull(reloaded.getLatencyMs());
        });

        assertEquals(1, attemptRepository.findAll().size());

        org.springframework.amqp.core.Message deadLettered =
                rabbitTemplate.receive(RabbitMqConfig.DEADLETTER_QUEUE, 5000);
        assertNotNull(deadLettered, "expected the attempt id on " + RabbitMqConfig.DEADLETTER_QUEUE);
        assertEquals(attempt.getId().toString(), new String(deadLettered.getBody()));
    }

    @Test
    void non2xxResponse_onAttemptBeforeFinal_createsFinalRetry() {
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(503)
                        .setBody("service unavailable"));

        int attemptNo = RetryTier.MAX_ATTEMPTS - 1;

        Attempt attempt = persistAttempt(
                mockWebServer.url("/webhook").toString(),
                attemptNo);

        attemptPublisher.publish(attempt.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt original = attemptRepository
                    .findById(attempt.getId())
                    .orElseThrow();

            assertEquals(
                    AttemptStatus.FAILED_RETRYING,
                    original.getStatus());

            assertEquals(503, original.getResponseCode());
            assertEquals(
                    "service unavailable",
                    original.getResponseBody());

            assertNull(original.getLastError());
            assertNotNull(original.getLatencyMs());
            assertNotNull(original.getNextRetryAt());
        });

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<Attempt> attempts = attemptRepository.findAll();

            assertEquals(2, attempts.size());

            Attempt retry = attempts.stream()
                    .filter(a -> !a.getId().equals(attempt.getId()))
                    .findFirst()
                    .orElseThrow();

            assertEquals(
                    RetryTier.MAX_ATTEMPTS,
                    retry.getAttemptNo());
            assertEquals(AttemptStatus.SCHEDULED, retry.getStatus());
            assertNotNull(retry.getNextRetryAt());
        });
    }

    @Test
    void unexpectedException_doesNotRequeueMessage() throws InterruptedException {
        // A malformed UUID throws inside onMessage() itself, BEFORE claim() runs - so unlike an
        // exception thrown from deliver() (which claim()'s idempotency check would prevent from
        // repeating on redelivery, since a second delivery just finds the attempt already
        // IN_FLIGHT and exits cleanly), this exception is identical on every redelivery. This is
        // the one path that can actually loop forever without default-requeue-rejected=false, so
        // it's the only scenario that genuinely proves the property. Published directly via
        // rabbitTemplate, bypassing AttemptPublisher, since it only ever sends real UUIDs.
        //
        // channel.messageCount(TASKS_QUEUE) is NOT a reliable signal here: a fast requeue loop
        // keeps the message perpetually "delivered but unacked" rather than sitting "ready" in
        // the queue, so a queue-depth reading (even a single non-polled one after a delay) almost
        // always reads 0 regardless of whether it's actually looping - confirmed empirically by
        // temporarily flipping default-requeue-rejected to true and observing 8000+ redeliveries
        // in 12 seconds while messageCount stayed 0. Instead, count how many times Spring AMQP's
        // container error handler actually logs a failed delivery: exactly once if the message is
        // dropped after the first failure, a rapidly growing count if it's looping.
        Logger errorHandlerLogger = (Logger) LoggerFactory
                .getLogger("org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler");
        AtomicInteger failureCount = new AtomicInteger(0);
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (event.getFormattedMessage().contains("Execution of Rabbit message listener failed")) {
                    failureCount.incrementAndGet();
                }
            }
        };
        appender.start();
        errorHandlerLogger.addAppender(appender);

        try {
            rabbitTemplate.convertAndSend(RabbitMqConfig.DELIVERY_EXCHANGE, RabbitMqConfig.TASKS_ROUTING_KEY,
                    "not-a-valid-uuid");

            // Give the listener time to consume the message - and, if requeue were enabled, to
            // already be looping many times over - before taking a reading.
            Thread.sleep(2000);

            assertEquals(1, failureCount.get(),
                    "expected exactly one failed delivery, not a requeue loop");
        } finally {
            errorHandlerLogger.detachAppender(appender);
        }
    }
}
