package com.example.relay.deliveryengine.worker;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.deliveryengine.config.RabbitMqConfig;
import com.example.relay.deliveryengine.publisher.AttemptPublisher;
import com.example.relay.deliveryengine.retry.RetryJitterSource;
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
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;

import com.example.relay.deliveryengine.config.DeliveryHttpClientConfig;
import com.example.relay.deliveryengine.destination.HostAddressLookup;
import com.example.relay.deliveryengine.destination.PolicyEnforcingDnsResolver;
import com.example.relay.deliveryengine.destination.PublicDestinationAddressPolicy;
import com.example.relay.deliveryengine.destination.SpecialPurposeAddressCatalog;
import com.example.relay.deliveryengine.http.BoundedApacheResponseBodyConsumer;
import com.example.relay.deliveryengine.http.ApacheWebhookHttpTransport;
import com.example.relay.deliveryengine.http.WebhookHttpTransport;

@Tag("integration")
@SpringBootTest
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Import(DeliveryWorkerIntegrationTest.LoopbackWebhookTransportConfiguration.class)
@TestPropertySource(properties = {
        "relay.retry.scheduling-enabled=false",
        "relay.reconciliation.scheduling-enabled=false"
})
public class DeliveryWorkerIntegrationTest implements SharedPostgresContainer {

    private static final Instant FIXED_RETRY_NOW = Instant.parse("2026-09-20T12:00:00Z");
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4.3.6-management");

    @Autowired
    private AttemptPublisher attemptPublisher;

    @Autowired
    private AttemptRepository attemptRepository;

    @Autowired
    private AttemptService attemptService;

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
    private Clock clock;

    @MockitoBean
    private RetryJitterSource retryJitterSource;

    private MockWebServer mockWebServer;
    private static final AtomicInteger resolverInvocations = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        resolverInvocations.set(0);
        when(clock.instant()).thenReturn(FIXED_RETRY_NOW);
        when(retryJitterSource.next(any(Duration.class))).thenReturn(Duration.ZERO);
        clearDatabase();
        drainQueues();
        rabbitListenerEndpointRegistry.getListenerContainer("deadLetterNotifier").stop();

        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    private void drainQueues() {
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
        emailVerificationTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @AfterEach
    void tearDown() throws IOException {
        rabbitListenerEndpointRegistry.getListenerContainer("deadLetterNotifier").start();
        mockWebServer.shutdown();
    }

    private String webhookUrl(String path) {
        return "http://webhook.test:" + mockWebServer.getPort() + path;
    }

    private Attempt persistAttempt(String url, int attemptNo) {
        return persistAttempt(url, attemptNo, new ObjectMapper().createObjectNode().put("amount", 4999));
    }

    private Attempt persistAttempt(String url, int attemptNo, String payload) {
        return persistAttempt(url, attemptNo, new ObjectMapper().createObjectNode().put("payload", payload));
    }

    private Attempt persistAttempt(String url, int attemptNo, ObjectNode body) {
        User user = userRepository.save(new User("test" + UUID.randomUUID().toString() + "@mail.com", "passwordHash"));
        Environment env = environmentRepository.save(new Environment("Env 1", "Desc 1", user));
        App app = appRepository.save(new App("App 1", env));
        Event event = eventRepository.save(new Event("payment.completed", app));
        Endpoint endpoint = endpointRepository.save(new Endpoint("EP 1", url, "whsec_1", app));
        Message message = messageRepository.save(new Message(app, event, body));
        Delivery delivery = deliveryRepository.save(new Delivery(app, message, endpoint));

        return attemptRepository.save(new Attempt(app, message, endpoint, delivery, attemptNo));
    }

    private static Stream<Arguments> payloadFixtures() {
        return Stream.of(
                Arguments.of(
                        "ASCII",
                        "ascii",
                        "{\"payload\":\"ascii\"}".getBytes(StandardCharsets.UTF_8)),
                Arguments.of(
                        "ordinary Unicode café 世界",
                        "café 世界",
                        "{\"payload\":\"café 世界\"}".getBytes(StandardCharsets.UTF_8)),
                Arguments.of(
                        "supplementary Unicode surrogate pair",
                        "\uD83D\uDE00",
                        "{\"payload\":\"\uD83D\uDE00\"}".getBytes(StandardCharsets.UTF_8)),
                Arguments.of(
                        "JSON escaping quotes backslashes newline tab control",
                        "quote\" slash\\ newline\n tab\t control\u0001",
                        "{\"payload\":\"quote\\\" slash\\\\ newline\\n tab\\t control\\u0001\"}"
                                .getBytes(StandardCharsets.UTF_8)));
    }

    private static String v1SignatureOverRawBody(String relayId, String timestamp, byte[] rawBody) {
        byte[] prefix = (relayId + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signedContent = new byte[prefix.length + rawBody.length];
        System.arraycopy(prefix, 0, signedContent, 0, prefix.length);
        System.arraycopy(rawBody, 0, signedContent, prefix.length, rawBody.length);

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("whsec_1".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(signedContent));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("Failed to compute test HMAC signature", ex);
        }
    }

    @ParameterizedTest(name = "{0} payload catches text/plain ISO-8859-1 transport instead of JSON UTF-8")
    @MethodSource("payloadFixtures")
    void deliveryWorker_preservesJsonUtf8AndRawBodySignature_insteadOfTextPlainIso88591Transport(
            String fixtureName, String payload, byte[] expectedJsonUtf8) throws InterruptedException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        Attempt attempt = persistAttempt(webhookUrl("/webhook"), 1, payload);

        attemptPublisher.publish(attempt.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(AttemptStatus.SUCCEEDED, reloaded.getStatus(), fixtureName);
        });

        RecordedRequest request = mockWebServer.takeRequest(10, TimeUnit.SECONDS);
        assertNotNull(request, fixtureName + " request was not received");
        byte[] rawBody = request.getBody().readByteArray();

        assertAll(
                fixtureName,
                () -> assertArrayEquals(expectedJsonUtf8, rawBody,
                        fixtureName + " must be sent as the exact hand-derived UTF-8 JSON bytes"),
                () -> {
                    MediaType contentType = MediaType.parseMediaType(request.getHeader("Content-Type"));
                    assertEquals(MediaType.APPLICATION_JSON,
                            new MediaType(contentType.getType(), contentType.getSubtype()),
                            fixtureName + " must declare JSON content type");
                },
                () -> assertEquals(
                        v1SignatureOverRawBody(
                                request.getHeader("relay-id"),
                                request.getHeader("relay-timestamp"),
                                rawBody),
                        request.getHeader("relay-signature"),
                        fixtureName + " relay-signature must cover the exact received body bytes"));
    }

    @Test
    void successfulDelivery_marksAttemptSucceeded() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        Attempt attempt = persistAttempt(webhookUrl("/webhook"), 1);

        attemptPublisher.publish(attempt.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(AttemptStatus.SUCCEEDED, reloaded.getStatus());
            assertEquals(200, reloaded.getResponseCode());
            assertEquals("ok", reloaded.getResponseBody());
        });
        assertEquals(1, resolverInvocations.get(), "public webhook.test destination must use the enforcing resolver");
        assertEquals(1, mockWebServer.getRequestCount());
    }

    @Test
    void largeSuccessfulResponse_isBoundedAndStillCommitsSuccess() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("x".repeat(11_000)));
        Attempt attempt = persistAttempt(webhookUrl("/webhook"), 1);

        attemptPublisher.publish(attempt.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(AttemptStatus.SUCCEEDED, reloaded.getStatus());
            assertEquals(200, reloaded.getResponseCode());
            assertTrue(reloaded.getResponseBody().length() <= 10_240);
            assertTrue(reloaded.getResponseBody().endsWith("[relay response truncated at 10240 bytes]"));
        });

        RecordedRequest firstRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(firstRequest);
        assertEquals(0, firstRequest.getSequenceNumber());
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("replacement response"));
        Attempt replacementAttempt = persistAttempt(webhookUrl("/replacement"), 1);
        attemptPublisher.publish(replacementAttempt.getId());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(AttemptStatus.SUCCEEDED,
                        attemptRepository.findById(replacementAttempt.getId()).orElseThrow().getStatus()));
        RecordedRequest replacementRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(replacementRequest);
        assertEquals(0, replacementRequest.getSequenceNumber(),
                "truncating a fixed-length response must discard its connection");
        assertEquals(0, attemptService.resetStuck(
                attempt.getId(), Instant.now().plusSeconds(1), Instant.now()),
                "a successfully completed attempt must not become executable through IN_FLIGHT recovery");
    }

    @Test
    void largeNon2xxResponse_isBoundedAndParentAndRetryCommitAtomically() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("x".repeat(50_000)));
        Attempt attempt = persistAttempt(webhookUrl("/webhook"), 1);

        attemptPublisher.publish(attempt.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt parent = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(AttemptStatus.FAILED_RETRYING, parent.getStatus());
            assertEquals(500, parent.getResponseCode());
            assertTrue(parent.getResponseBody().length() <= 10_240);
            assertTrue(parent.getResponseBody().endsWith("[relay response truncated at 10240 bytes]"));

            List<Attempt> attempts = attemptRepository.findAll();
            assertEquals(2, attempts.size());
            Attempt retry = attempts.stream()
                    .filter(candidate -> !candidate.getId().equals(attempt.getId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(2, retry.getAttemptNo());
            assertEquals(AttemptStatus.SCHEDULED, retry.getStatus());
        });

        RecordedRequest firstRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(firstRequest);
        assertEquals(0, firstRequest.getSequenceNumber());
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("replacement response"));
        Attempt replacementAttempt = persistAttempt(webhookUrl("/replacement"), 1);
        attemptPublisher.publish(replacementAttempt.getId());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(AttemptStatus.SUCCEEDED,
                        attemptRepository.findById(replacementAttempt.getId()).orElseThrow().getStatus()));
        RecordedRequest replacementRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(replacementRequest);
        assertEquals(0, replacementRequest.getSequenceNumber(),
                "truncating a non-2xx fixed-length response must discard its connection");
    }

    @Test
    void chunkedResponseWithoutContentLength_abortsUnreadRemainderAndReleasesTheExchange() throws InterruptedException {
        // The real Apache transport must finish after the bounded diagnostic prefix, not after a
        // caller buffers the full response. At this rate the complete body takes roughly 24 seconds,
        // while the 10,241 bytes Relay reads arrive in well under one second.
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setChunkedBody("x".repeat(1_000_000), 257)
                .throttleBody(1_024, 25, TimeUnit.MILLISECONDS));
        Attempt attempt = persistAttempt(webhookUrl("/webhook"), 1);

        attemptPublisher.publish(attempt.getId());

        await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(AttemptStatus.SUCCEEDED, reloaded.getStatus());
            assertTrue(reloaded.getResponseBody().length() <= 10_240);
            assertTrue(reloaded.getResponseBody().endsWith("[relay response truncated at 10240 bytes]"));
        });

        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("second response"));
        Attempt secondAttempt = persistAttempt(webhookUrl("/webhook"), 1);
        attemptPublisher.publish(secondAttempt.getId());

        await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(secondAttempt.getId()).orElseThrow();
            assertEquals(AttemptStatus.SUCCEEDED, reloaded.getStatus());
            assertEquals("second response", reloaded.getResponseBody());
        });
        RecordedRequest firstRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest secondRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(firstRequest);
        assertNotNull(secondRequest);
        assertEquals(0, firstRequest.getSequenceNumber());
        assertEquals(0, secondRequest.getSequenceNumber(),
                "truncating a chunked response must discard its connection");
        assertEquals(2, mockWebServer.getRequestCount());
    }

    @Test
    void oversizedChunkedNon2xxResponse_isBoundedAndReplacesTheConnection() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setChunkedBody("x".repeat(1_000_000), 257)
                .throttleBody(1_024, 25, TimeUnit.MILLISECONDS));
        Attempt attempt = persistAttempt(webhookUrl("/chunked-failure"), 1);

        attemptPublisher.publish(attempt.getId());

        await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
            Attempt parent = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(AttemptStatus.FAILED_RETRYING, parent.getStatus());
            assertEquals(500, parent.getResponseCode());
            assertTrue(parent.getResponseBody().length() <= 10_240);
            assertTrue(parent.getResponseBody().endsWith("[relay response truncated at 10240 bytes]"));
            assertEquals(2, attemptRepository.findAll().size());
        });

        RecordedRequest firstRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(firstRequest);
        assertEquals(0, firstRequest.getSequenceNumber());
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("replacement response"));
        Attempt replacementAttempt = persistAttempt(webhookUrl("/replacement"), 1);
        attemptPublisher.publish(replacementAttempt.getId());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(AttemptStatus.SUCCEEDED,
                        attemptRepository.findById(replacementAttempt.getId()).orElseThrow().getStatus()));
        RecordedRequest replacementRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(replacementRequest);
        assertEquals(0, replacementRequest.getSequenceNumber(),
                "truncating a chunked non-2xx response must discard its connection");
    }

    @Test
    void smallEofResponses_mayReuseApacheConnectionThroughWorker() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("first"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("second"));
        Attempt firstAttempt = persistAttempt(webhookUrl("/first"), 1);
        Attempt secondAttempt = persistAttempt(webhookUrl("/second"), 1);

        attemptPublisher.publish(firstAttempt.getId());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(AttemptStatus.SUCCEEDED,
                        attemptRepository.findById(firstAttempt.getId()).orElseThrow().getStatus()));
        attemptPublisher.publish(secondAttempt.getId());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(AttemptStatus.SUCCEEDED,
                        attemptRepository.findById(secondAttempt.getId()).orElseThrow().getStatus()));

        RecordedRequest firstRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest secondRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(firstRequest);
        assertNotNull(secondRequest);
        assertEquals(0, firstRequest.getSequenceNumber());
        assertEquals(1, secondRequest.getSequenceNumber(), "small EOF response may return connection to pool");
    }

    @Test
    void emptySuccessfulResponse_preservesNullDiagnosticBody() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(204));
        Attempt attempt = persistAttempt(webhookUrl("/webhook"), 1);

        attemptPublisher.publish(attempt.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(AttemptStatus.SUCCEEDED, reloaded.getStatus());
            assertEquals(204, reloaded.getResponseCode());
            assertNull(reloaded.getResponseBody());
        });
    }

    @Test
    void emptyNon2xxResponse_preservesNullDiagnosticBodyAndCreatesRetry() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));
        Attempt attempt = persistAttempt(webhookUrl("/webhook"), 1);

        attemptPublisher.publish(attempt.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(AttemptStatus.FAILED_RETRYING, reloaded.getStatus());
            assertEquals(500, reloaded.getResponseCode());
            assertNull(reloaded.getResponseBody());
            assertEquals(2, attemptRepository.findAll().size());
        });
    }

    @Test
    void responseBodyTimeoutAfterHeaders_preservesExistingTransportFailureSemantics() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("ok")
                .setBodyDelay(16, TimeUnit.SECONDS));
        Attempt attempt = persistAttempt(webhookUrl("/webhook"), 1);

        attemptPublisher.publish(attempt.getId());

        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(AttemptStatus.FAILED_RETRYING, reloaded.getStatus());
            assertNull(reloaded.getResponseCode());
            assertNull(reloaded.getResponseBody());
            assertNotNull(reloaded.getLastError());
            assertEquals(2, attemptRepository.findAll().size());
        });
    }

    @Test
    void non2xxResponse_beforeFinalAttempt_createsScheduledRetryWithoutPublishingTask() {
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(500)
                        .setBody("internal error"));

        Attempt attempt = persistAttempt(
                webhookUrl("/webhook"),
                1);

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

            Instant expected = FIXED_RETRY_NOW.plus(tier.getDelay());

            assertEquals(expected, original.getNextRetryAt(),
                    "nextAttemptAt should use the application Clock and retry delay");
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

        assertNull(rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 1000),
                "scheduled retry must not be published to the worker queue");
    }

    @Test
    void redirectResponse_isNotFollowed_andTreatedAsFailure() {
        // The delivery transport explicitly disables redirects. A 302 from the endpoint must therefore
        // be recorded as the delivery outcome itself - a non-2xx failure - not silently followed to
        // whatever the Location header points at.
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", "/somewhere-else")
                        .setBody("redirecting"));

        Attempt attempt = persistAttempt(
                webhookUrl("/webhook"),
                1);

        attemptPublisher.publish(attempt.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt original = attemptRepository
                    .findById(attempt.getId())
                    .orElseThrow();

            assertEquals(AttemptStatus.FAILED_RETRYING, original.getStatus());
            assertEquals(302, original.getResponseCode());
        });

        // Exactly one request reached the mock server - if the redirect had been followed, a second
        // request to /somewhere-else would show up too.
        assertEquals(1, mockWebServer.getRequestCount());
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

    @ParameterizedTest(name = "protected destination {0} is rejected before a socket is opened")
    @MethodSource("protectedDestinationFixtures")
    void protectedDestination_isRejectedThroughWorkerLifecycleWithoutOpeningListener(
            String fixtureName, String destinationHost, int expectedResolverInvocations) {
        Attempt attempt = persistAttempt("http://" + destinationHost + ":" + mockWebServer.getPort() + "/webhook", 1);

        attemptPublisher.publish(attempt.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(AttemptStatus.FAILED_RETRYING, reloaded.getStatus(), fixtureName);
            assertTrue(reloaded.getLastError().startsWith("DESTINATION_POLICY_BLOCKED: "), fixtureName);
            List<Attempt> attempts = attemptRepository.findAll();
            assertEquals(2, attempts.size(), fixtureName);
            Attempt retry = attempts.stream()
                    .filter(candidate -> !candidate.getId().equals(attempt.getId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(2, retry.getAttemptNo(), fixtureName);
            assertEquals(AttemptStatus.SCHEDULED, retry.getStatus(), fixtureName);
        });

        assertEquals(expectedResolverInvocations, resolverInvocations.get(), fixtureName);
        assertEquals(0, mockWebServer.getRequestCount(), fixtureName + " must open no listener socket");
    }

    private static Stream<Arguments> protectedDestinationFixtures() {
        return Stream.of(
                Arguments.of("protected literal", "0.0.0.0", 0),
                Arguments.of("protected DNS", "blocked.webhook.test", 1),
                Arguments.of("mixed DNS answers", "mixed.webhook.test", 1));
    }

    @Test
    void non2xxResponse_onFinalAttempt_marksAttemptDead() {
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(500)
                        .setBody("internal error"));

        Attempt attempt = persistAttempt(
                webhookUrl("/webhook"),
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
                webhookUrl("/webhook"),
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
        // in 12 seconds while messageCount stayed 0. Instead, count how many times Spring AMQP
        // actually logs a failed delivery: exactly once if the message is dropped after the first
        // failure, a rapidly growing count if it's looping.
        //
        // Since Task 3, onMessage returns a CompletableFuture<Void>, so an exception thrown inside
        // the CompletableFuture.runAsync Runnable never reaches the container's synchronous
        // ConditionalRejectingErrorHandler ("Execution of Rabbit message listener failed") - it's
        // routed instead through MessagingMessageListenerAdapter.asyncFailure, which logs via its
        // own logger and calls channel.basicReject(tag, requeue) directly using the same
        // default-requeue-rejected-derived ContainerUtils.shouldRequeue(...) decision. Observe that
        // path's logger/message instead; the no-requeue-loop property being proven is unchanged.
        Logger errorHandlerLogger = (Logger) LoggerFactory
                .getLogger("org.springframework.amqp.rabbit.listener.adapter.MessagingMessageListenerAdapter");
        AtomicInteger failureCount = new AtomicInteger(0);
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (event.getFormattedMessage()
                        .contains("Future, Mono, or suspend function was completed with an exception for")) {
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

    @TestConfiguration(proxyBeanMethods = false)
    static class LoopbackWebhookTransportConfiguration {

        @Bean(name = "loopbackDeliveryConnectionManager", destroyMethod = "close")
        @Primary
        PoolingHttpClientConnectionManager loopbackDeliveryConnectionManager() {
            DeliveryHttpClientConfig config = new DeliveryHttpClientConfig();
            HostAddressLookup lookup = (absoluteHostname, deadline) -> {
                resolverInvocations.incrementAndGet();
                if ("blocked.webhook.test.".equals(absoluteHostname)) {
                    return List.of(protectedZeroAddress());
                }
                if ("mixed.webhook.test.".equals(absoluteHostname)) {
                    return List.of(InetAddress.getLoopbackAddress(), protectedZeroAddress());
                }
                return List.of(InetAddress.getLoopbackAddress());
            };
            PolicyEnforcingDnsResolver resolver = new PolicyEnforcingDnsResolver(lookup,
                    loopbackPermittingPolicy());
            return config.deliveryConnectionManager(resolver);
        }

        @Bean(name = "loopbackDeliveryApacheHttpClient", destroyMethod = "close")
        @Primary
        CloseableHttpClient loopbackDeliveryApacheHttpClient(
                @Qualifier("loopbackDeliveryConnectionManager") PoolingHttpClientConnectionManager manager) {
            return new DeliveryHttpClientConfig().deliveryApacheHttpClient(manager);
        }

        @Bean
        @Primary
        WebhookHttpTransport loopbackWebhookHttpTransport(
                @Qualifier("loopbackDeliveryApacheHttpClient") CloseableHttpClient client) {
            return new ApacheWebhookHttpTransport(client, new BoundedApacheResponseBodyConsumer());
        }

        private static PublicDestinationAddressPolicy loopbackPermittingPolicy() {
            byte[] sentinelCatalog = "0.0.0.0/32|TEST_SENTINEL\n::/128|TEST_SENTINEL\n"
                    .getBytes(StandardCharsets.UTF_8);
            return new PublicDestinationAddressPolicy(
                    new SpecialPurposeAddressCatalog(new ByteArrayInputStream(sentinelCatalog)));
        }

        private static InetAddress protectedZeroAddress() {
            try {
                return InetAddress.getByAddress(new byte[] {0, 0, 0, 0});
            } catch (java.net.UnknownHostException exception) {
                throw new AssertionError("fixed test address must be valid", exception);
            }
        }
    }
}
