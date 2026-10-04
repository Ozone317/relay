package com.example.relay.deliveryengine.worker;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.application.AttemptExecution;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.deliveryengine.config.RabbitMqConfig;
import com.example.relay.deliveryengine.dispatcher.ReadyWorkDispatcher;
import com.example.relay.deliveryengine.http.WebhookHeaders;
import com.example.relay.deliveryengine.http.WebhookHttpResponse;
import com.example.relay.deliveryengine.http.WebhookHttpTransport;
import com.example.relay.deliveryengine.publisher.AttemptPublisher;
import com.example.relay.deliveryengine.signing.HmacSigner;
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
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@SpringBootTest
@Testcontainers
@EnableTestBackgroundExecution(TestBackgroundComponent.RABBIT_LISTENERS)
class DeliveryWorkerOwnershipFencingIntegrationTest implements SharedPostgresContainer {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQ = new RabbitMQContainer("rabbitmq:4.3.6-management");

    @Autowired AttemptPublisher attemptPublisher;
    @MockitoSpyBean AttemptService attemptService;
    @Autowired ReadyWorkDispatcher dispatcher;
    @Autowired AttemptRepository attemptRepository;
    @Autowired DeliveryRepository deliveryRepository;
    @Autowired MessageRepository messageRepository;
    @Autowired EndpointRepository endpointRepository;
    @Autowired EventRepository eventRepository;
    @Autowired AppRepository appRepository;
    @Autowired EnvironmentRepository environmentRepository;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired EmailVerificationTokenRepository emailVerificationTokenRepository;
    @Autowired RabbitListenerEndpointRegistry listenerRegistry;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meterRegistry;
    @Autowired RabbitTemplate rabbitTemplate;
    private final HttpClient managementClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @MockitoBean WebhookHttpTransport transport;
    @MockitoBean HmacSigner signer;
    private Logger workerLogger;
    private ListAppender<ILoggingEvent> ownershipLogAppender;

    @BeforeEach
    void setUp() {
        workerLogger = (Logger) LoggerFactory.getLogger(DeliveryWorker.class);
        ownershipLogAppender = new ListAppender<>();
        ownershipLogAppender.start();
        workerLogger.addAppender(ownershipLogAppender);
        when(signer.sign(anyString(), any(Long.class), any(byte[].class), anyString())).thenReturn("signature");
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
        listenerRegistry.getListenerContainer("deadLetterNotifier").stop();
    }

    @AfterEach
    void restartNotifier() {
        workerLogger.detachAppender(ownershipLogAppender);
        ownershipLogAppender.stop();
        listenerRegistry.getListenerContainer("deadLetterNotifier").start();
    }

    private Attempt persistAttempt(int attemptNo) {
        User user = userRepository.save(new User("ownership-" + UUID.randomUUID() + "@mail.test", "hash"));
        Environment env = environmentRepository.save(new Environment("Env", "Desc", user));
        App app = appRepository.save(new App("App", env));
        Event event = eventRepository.save(new Event("payment.completed", app));
        Endpoint endpoint = endpointRepository.save(new Endpoint("EP", "http://example.test/hook", "secret", app));
        Message message = messageRepository.save(new Message(app, event,
                new ObjectMapper().createObjectNode().put("amount", 1)));
        Delivery delivery = deliveryRepository.save(new Delivery(app, message, endpoint));
        return attemptRepository.save(new Attempt(app, message, endpoint, delivery, attemptNo));
    }

    private long unacknowledgedCount(String queue) throws Exception {
        String auth = Base64.getEncoder().encodeToString((rabbitMQ.getAdminUsername() + ":"
                + rabbitMQ.getAdminPassword()).getBytes());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(rabbitMQ.getHttpUrl() + "/api/queues/%2f/" + queue))
                .header("Authorization", "Basic " + auth).GET().build();
        HttpResponse<String> response = managementClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(response.body());
        return json.path("messages_unacknowledged").asLong(-1);
    }

    @Test
    void staleSuccessAfterNewOwnerFailure_isAcknowledgedWithoutRetryOrFurtherTransport() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Attempt original = persistAttempt(1);
        AtomicReference<Instant> newerFailureReturnedAt = new AtomicReference<>();
        doAnswer(invocation -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstEntered.countDown();
                if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("first transport stayed blocked");
                }
                return new WebhookHttpResponse(200, "stale success");
            }
            newerFailureReturnedAt.set(Instant.now());
            return new WebhookHttpResponse(503, "current failure");
        }).when(transport).post(anyString(), any(byte[].class), any(WebhookHeaders.class));
        attemptPublisher.publish(original.getId());
        assertTrue(firstEntered.await(10, TimeUnit.SECONDS));
        jdbc.update("UPDATE attempts SET execution_claimed_at = CURRENT_TIMESTAMP - INTERVAL '1 hour', "
                + "updated_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE id = ?",
                original.getId());
        assertEquals(1, attemptService.resetStuck(original.getId(), java.time.Instant.now().plusSeconds(60),
                java.time.Instant.now()));
        dispatcher.dispatchOnce();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(AttemptStatus.FAILED_RETRYING,
                        attemptRepository.findById(original.getId()).orElseThrow().getStatus()));
        releaseFirst.countDown();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(2, calls.get()));
        assertEquals(2, attemptRepository.findAll().size());
        Attempt current = attemptRepository.findById(original.getId()).orElseThrow();
        assertEquals(AttemptStatus.FAILED_RETRYING, current.getStatus());
        assertEquals(503, current.getResponseCode());
        assertEquals("current failure", current.getResponseBody());
        assertNull(current.getLastError());
        assertNotNull(current.getLatencyMs());
        assertNull(current.getDeadLetterNotifiedAt());
        assertNotNull(current.getNextRetryAt());
        assertTrue(!current.getNextRetryAt().isBefore(newerFailureReturnedAt.get().plusSeconds(30)));
        assertTrue(!current.getNextRetryAt().isAfter(newerFailureReturnedAt.get().plusSeconds(38)));
        Attempt child = attemptRepository.findAll().stream()
                .filter(candidate -> !candidate.getId().equals(original.getId()))
                .findFirst().orElseThrow();
        assertEquals(AttemptStatus.SCHEDULED, child.getStatus());
        assertEquals(2, child.getAttemptNo());
        assertEquals(current.getNextRetryAt(), child.getNextRetryAt());
        assertEquals(current.getDelivery().getId(), child.getDelivery().getId());
        assertEquals(current.getMessage().getId(), child.getMessage().getId());
        assertEquals(current.getEndpoint().getId(), child.getEndpoint().getId());
        assertEquals(current.getApp().getId(), child.getApp().getId());
        assertNull(child.getResponseCode());
        assertNull(child.getResponseBody());
        assertNull(child.getLastError());
        assertNull(child.getLatencyMs());
        assertNull(child.getExecutionClaimedAt());
        assertEquals(0, child.getExecutionGeneration());
        assertNull(child.getDeadLetterNotifiedAt());
        assertNull(child.getReadyPublishedAt());
        assertNull(child.getReadyDispatchClaimId());
        assertNull(child.getReadyDispatchClaimedAt());
        assertEquals(1.0, meterRegistry.get("relay.delivery.execution.ownership.lost")
                .tag("completion", "SUCCEEDED").tag("current_status", "FAILED_RETRYING").counter().count());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertTrue(ownershipLogAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.INFO
                        && event.getFormattedMessage().contains("completion SUCCEEDED lost execution ownership")
                        && event.getFormattedMessage().contains("current status is FAILED_RETRYING"))));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(0, unacknowledgedCount(RabbitMqConfig.TASKS_QUEUE)));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(2, calls.get()));
    }

    @Test
    void staleFailureAfterNewOwnerSuccess_doesNotCreateRetryOrOverwriteWinner() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                if (!releaseFirst.await(10, TimeUnit.SECONDS)) throw new AssertionError("first transport stayed blocked");
                return new WebhookHttpResponse(503, "stale failure");
            }
            return new WebhookHttpResponse(200, "current success");
        }).when(transport).post(anyString(), any(byte[].class), any(WebhookHeaders.class));
        Attempt original = persistAttempt(1);
        attemptPublisher.publish(original.getId());
        assertTrue(firstEntered.await(10, TimeUnit.SECONDS));
        jdbc.update("UPDATE attempts SET execution_claimed_at = CURRENT_TIMESTAMP - INTERVAL '1 hour', "
                + "updated_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE id = ?", original.getId());
        assertEquals(1, attemptService.resetStuck(original.getId(), java.time.Instant.now().plusSeconds(60),
                java.time.Instant.now()));
        dispatcher.dispatchOnce();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(AttemptStatus.SUCCEEDED,
                        attemptRepository.findById(original.getId()).orElseThrow().getStatus()));
        releaseFirst.countDown();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertEquals(2, calls.get());
            assertEquals(1.0, meterRegistry.get("relay.delivery.execution.ownership.lost")
                    .tag("completion", "FAILED_RETRYING").tag("current_status", "SUCCEEDED").counter().count());
        });
        Attempt current = attemptRepository.findById(original.getId()).orElseThrow();
        assertEquals(200, current.getResponseCode());
        assertEquals("current success", current.getResponseBody());
        assertEquals(1, attemptRepository.findAll().size());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(0, unacknowledgedCount(RabbitMqConfig.TASKS_QUEUE)));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(2, calls.get()));
    }

    @Test
    void duplicateTaskWhileInFlightAndAfterTerminal_isAcknowledgedWithoutTransport() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch duplicateClaimEmptyWhileInFlight = new CountDownLatch(1);
        CountDownLatch duplicateClaimEmptyAfterTerminal = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            calls.incrementAndGet();
            entered.countDown();
            if (!release.await(60, TimeUnit.SECONDS)) throw new AssertionError("transport stayed blocked");
            return new WebhookHttpResponse(204, "ok");
        }).when(transport).post(anyString(), any(byte[].class), any(WebhookHeaders.class));
        Attempt attempt = persistAttempt(1);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Optional<AttemptExecution> result = (Optional<AttemptExecution>) invocation.callRealMethod();
            if (result.isEmpty()) {
                AttemptStatus status = attemptRepository.findById(attempt.getId()).orElseThrow().getStatus();
                if (status == AttemptStatus.IN_FLIGHT) {
                    duplicateClaimEmptyWhileInFlight.countDown();
                } else if (status == AttemptStatus.SUCCEEDED) {
                    duplicateClaimEmptyAfterTerminal.countDown();
                }
            }
            return result;
        }).when(attemptService).claim(eq(attempt.getId()));
        attemptPublisher.publish(attempt.getId());
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(1, unacknowledgedCount(RabbitMqConfig.TASKS_QUEUE)));
        attemptPublisher.publish(attempt.getId());
        assertTrue(duplicateClaimEmptyWhileInFlight.await(10, TimeUnit.SECONDS),
                "duplicate must reach the real claim-empty path while A is still IN_FLIGHT");
        await().during(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertEquals(1, unacknowledgedCount(RabbitMqConfig.TASKS_QUEUE),
                    "only the original task should remain unacknowledged after duplicate drain");
            assertEquals(AttemptStatus.IN_FLIGHT,
                    attemptRepository.findById(attempt.getId()).orElseThrow().getStatus());
            assertEquals(1, calls.get());
        });
        release.countDown();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(AttemptStatus.SUCCEEDED, attemptRepository.findById(attempt.getId()).orElseThrow().getStatus()));
        attemptPublisher.publish(attempt.getId());
        assertTrue(duplicateClaimEmptyAfterTerminal.await(10, TimeUnit.SECONDS),
                "terminal duplicate must reach the real claim-empty path");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(1, calls.get()));
        assertFalse(attemptRepository.findById(attempt.getId()).orElseThrow().getStatus() == AttemptStatus.IN_FLIGHT);
        await().during(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertEquals(0, unacknowledgedCount(RabbitMqConfig.TASKS_QUEUE));
            assertEquals(1, calls.get());
        });
    }

    @Test
    void staleAttemptSixFailure_doesNotOverwriteDeadOrPublishDeadLetter() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("transport stayed blocked");
            return new WebhookHttpResponse(500, "stale failure");
        }).when(transport).post(anyString(), any(byte[].class), any(WebhookHeaders.class));
        Attempt attempt = persistAttempt(6);
        attemptPublisher.publish(attempt.getId());
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        jdbc.update("UPDATE attempts SET execution_claimed_at = CURRENT_TIMESTAMP - INTERVAL '1 hour', "
                + "updated_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE id = ?",
                attempt.getId());
        assertEquals(1, attemptService.resetStuck(attempt.getId(), java.time.Instant.now().plusSeconds(60),
                java.time.Instant.now()));
        var current = attemptService.claim(attempt.getId()).orElseThrow();
        assertEquals(com.example.relay.attempt.application.AttemptMutationOutcome.APPLIED,
                attemptService.markSucceeded(current, 204, "new owner", 1L));
        release.countDown();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertEquals(AttemptStatus.SUCCEEDED, attemptRepository.findById(attempt.getId()).orElseThrow().getStatus());
            assertEquals(1.0, meterRegistry.get("relay.delivery.execution.ownership.lost")
                    .tag("completion", "DEAD").tag("current_status", "SUCCEEDED").counter().count());
        });
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM attempts WHERE id <> ?", Integer.class, attempt.getId()));
        await().during(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertNull(rabbitTemplate.receive(RabbitMqConfig.DEADLETTER_QUEUE, 100),
                        "stale final failure must never publish a dead-letter task"));
    }
}
