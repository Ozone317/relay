package com.example.relay.deliveryengine.worker;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.example.relay.deliveryengine.destination.DnsResolutionException;
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
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
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
                () -> assertEquals(attempt.getMessage().getId().toString(), request.getHeader("relay-id"),
                        fixtureName + " relay-id must identify the persisted message"),
                () -> {
                    String timestamp = request.getHeader("relay-timestamp");
                    assertNotNull(timestamp, fixtureName + " relay-timestamp is required");
                    long timestampSeconds = Long.parseLong(timestamp);
                    assertTrue(timestampSeconds > 0, fixtureName + " relay-timestamp must be a positive epoch second");
                    long now = Instant.now().getEpochSecond();
                    assertTrue(Math.abs(now - timestampSeconds) <= 30,
                            fixtureName + " relay-timestamp must be current");
                },
                () -> assertEquals("application/json", request.getHeader("Content-Type"),
                        fixtureName + " must use the established content type without charset rewriting"),
                () -> {
                    String signature = request.getHeader("relay-signature");
                    assertTrue(signature.matches("v1,[A-Za-z0-9+/]{43}={0,2}"),
                            fixtureName + " relay-signature must use the v1 base64 format");
                    assertEquals(
                            v1SignatureOverRawBody(
                                    request.getHeader("relay-id"),
                                    request.getHeader("relay-timestamp"),
                                    rawBody),
                            signature,
                            fixtureName + " relay-signature must cover the exact received body bytes");
                });
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
    void largeSuccessfulResponse_isBoundedAndStillCommitsSuccess() throws IOException, InterruptedException {
        try (WorkerBoundedResponseFixture fixture = new WorkerBoundedResponseFixture(false, 200)) {
            Attempt attempt = persistAttempt(fixture.url("/webhook"), 1);

            attemptPublisher.publish(attempt.getId());

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
                assertEquals(AttemptStatus.SUCCEEDED, reloaded.getStatus());
                assertEquals(200, reloaded.getResponseCode());
                assertEquals(WorkerBoundedResponseFixture.EXPECTED_TRUNCATED_BODY, reloaded.getResponseBody());
            });

            assertTrue(fixture.firstLimitReached.await(5, TimeUnit.SECONDS));
            assertEquals(10_241, fixture.firstBodyBytesWritten.get());
            assertEquals(10_241, fixture.producerBodyBytesWritten.get(),
                    "the gated producer must not make the unread tail available before worker completion");
            assertTrue(!fixture.firstProducerFinished.get());
            fixture.releaseTail();
            assertTrue(fixture.tailStarted.await(5, TimeUnit.SECONDS));
            assertTrue(fixture.firstClientClosed.await(5, TimeUnit.SECONDS));
            assertTrue(!fixture.firstProducerFinished.get());
            assertTrue(fixture.producerBodyBytesWritten.get() < WorkerBoundedResponseFixture.BODY_LENGTH,
                    "the aborted endpoint must not drain the gated response tail");

            Attempt replacementAttempt = persistAttempt(fixture.url("/replacement"), 1);
            attemptPublisher.publish(replacementAttempt.getId());
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertEquals(AttemptStatus.SUCCEEDED,
                            attemptRepository.findById(replacementAttempt.getId()).orElseThrow().getStatus()));
            assertTrue(fixture.secondServed.await(5, TimeUnit.SECONDS));
            assertEquals(2, fixture.acceptedConnections.get(),
                    "truncating a fixed-length response must use a replacement socket");
            assertEquals(0, attemptService.resetStuck(
                    attempt.getId(), Instant.now().plusSeconds(1), Instant.now()),
                    "a successfully completed attempt must not become executable through IN_FLIGHT recovery");
        }
    }

    @Test
    void largeNon2xxResponse_isBoundedAndParentAndRetryCommitAtomically() throws IOException, InterruptedException {
        try (WorkerBoundedResponseFixture fixture = new WorkerBoundedResponseFixture(false, 500)) {
            Attempt attempt = persistAttempt(fixture.url("/webhook"), 1);

            attemptPublisher.publish(attempt.getId());

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Attempt parent = attemptRepository.findById(attempt.getId()).orElseThrow();
                assertEquals(AttemptStatus.FAILED_RETRYING, parent.getStatus());
                assertEquals(500, parent.getResponseCode());
                assertEquals(WorkerBoundedResponseFixture.EXPECTED_TRUNCATED_BODY, parent.getResponseBody());

                List<Attempt> attempts = attemptRepository.findAll();
                assertEquals(2, attempts.size());
                Attempt retry = attempts.stream()
                        .filter(candidate -> !candidate.getId().equals(attempt.getId()))
                        .findFirst()
                        .orElseThrow();
                assertEquals(2, retry.getAttemptNo());
                assertEquals(AttemptStatus.SCHEDULED, retry.getStatus());
            });

            assertTrue(fixture.firstLimitReached.await(5, TimeUnit.SECONDS));
            assertEquals(10_241, fixture.firstBodyBytesWritten.get());
            assertEquals(10_241, fixture.producerBodyBytesWritten.get());
            assertTrue(!fixture.firstProducerFinished.get());
            fixture.releaseTail();
            assertTrue(fixture.tailStarted.await(5, TimeUnit.SECONDS));
            assertTrue(fixture.firstClientClosed.await(5, TimeUnit.SECONDS));
            assertTrue(!fixture.firstProducerFinished.get());
            assertTrue(fixture.producerBodyBytesWritten.get() < WorkerBoundedResponseFixture.BODY_LENGTH);
            Attempt replacementAttempt = persistAttempt(fixture.url("/replacement"), 1);
            attemptPublisher.publish(replacementAttempt.getId());
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertEquals(AttemptStatus.SUCCEEDED,
                            attemptRepository.findById(replacementAttempt.getId()).orElseThrow().getStatus()));
            assertTrue(fixture.secondServed.await(5, TimeUnit.SECONDS));
            assertEquals(2, fixture.acceptedConnections.get(),
                    "truncating a non-2xx fixed-length response must use a replacement socket");
        }
    }

    @Test
    void chunkedResponseWithoutContentLength_abortsUnreadRemainderAndReleasesTheExchange()
            throws IOException, InterruptedException {
        try (WorkerBoundedResponseFixture fixture = new WorkerBoundedResponseFixture(true, 200)) {
            Attempt attempt = persistAttempt(fixture.url("/webhook"), 1);
            attemptPublisher.publish(attempt.getId());

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
                assertEquals(AttemptStatus.SUCCEEDED, reloaded.getStatus());
                assertEquals(WorkerBoundedResponseFixture.EXPECTED_TRUNCATED_BODY, reloaded.getResponseBody());
            });
            assertTrue(fixture.firstLimitReached.await(5, TimeUnit.SECONDS));
            assertEquals(10_241, fixture.firstBodyBytesWritten.get());
            assertEquals(10_241, fixture.producerBodyBytesWritten.get());
            assertTrue(!fixture.firstProducerFinished.get());
            fixture.releaseTail();
            assertTrue(fixture.tailStarted.await(5, TimeUnit.SECONDS));
            assertTrue(fixture.firstClientClosed.await(5, TimeUnit.SECONDS));
            assertTrue(!fixture.firstProducerFinished.get());
            assertTrue(fixture.producerBodyBytesWritten.get() < WorkerBoundedResponseFixture.BODY_LENGTH);

            Attempt secondAttempt = persistAttempt(fixture.url("/second"), 1);
            attemptPublisher.publish(secondAttempt.getId());
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Attempt reloaded = attemptRepository.findById(secondAttempt.getId()).orElseThrow();
                assertEquals(AttemptStatus.SUCCEEDED, reloaded.getStatus());
                assertEquals("second response", reloaded.getResponseBody());
            });
            assertTrue(fixture.secondServed.await(5, TimeUnit.SECONDS));
            assertEquals(2, fixture.acceptedConnections.get(),
                    "truncating a chunked response must use a replacement socket");
        }
    }

    @Test
    void oversizedChunkedNon2xxResponse_isBoundedAndReplacesTheConnection()
            throws IOException, InterruptedException {
        try (WorkerBoundedResponseFixture fixture = new WorkerBoundedResponseFixture(true, 500)) {
            Attempt attempt = persistAttempt(fixture.url("/chunked-failure"), 1);
            attemptPublisher.publish(attempt.getId());

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Attempt parent = attemptRepository.findById(attempt.getId()).orElseThrow();
                assertEquals(AttemptStatus.FAILED_RETRYING, parent.getStatus());
                assertEquals(500, parent.getResponseCode());
                assertEquals(WorkerBoundedResponseFixture.EXPECTED_TRUNCATED_BODY, parent.getResponseBody());
                assertEquals(2, attemptRepository.findAll().size());
            });
            assertTrue(fixture.firstLimitReached.await(5, TimeUnit.SECONDS));
            assertEquals(10_241, fixture.firstBodyBytesWritten.get());
            assertEquals(10_241, fixture.producerBodyBytesWritten.get());
            assertTrue(!fixture.firstProducerFinished.get());
            fixture.releaseTail();
            assertTrue(fixture.tailStarted.await(5, TimeUnit.SECONDS));
            assertTrue(fixture.firstClientClosed.await(5, TimeUnit.SECONDS));
            assertTrue(!fixture.firstProducerFinished.get());
            assertTrue(fixture.producerBodyBytesWritten.get() < WorkerBoundedResponseFixture.BODY_LENGTH);

            Attempt replacementAttempt = persistAttempt(fixture.url("/replacement"), 1);
            attemptPublisher.publish(replacementAttempt.getId());
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertEquals(AttemptStatus.SUCCEEDED,
                            attemptRepository.findById(replacementAttempt.getId()).orElseThrow().getStatus()));
            assertTrue(fixture.secondServed.await(5, TimeUnit.SECONDS));
            assertEquals(2, fixture.acceptedConnections.get(),
                    "truncating a chunked non-2xx response must use a replacement socket");
        }
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
                Arguments.of("protected DNS", "blocked.webhook.test", 1),
                Arguments.of("mixed DNS answers", "mixed.webhook.test", 1));
    }

    @Test
    void protectedLiteral_isRejectedBeforeConnectingToAProtectedListener() throws Exception {
        try (ServerSocket protectedTrap = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            protectedTrap.setSoTimeout(500);
            Attempt attempt = persistAttempt("http://0.0.0.0:" + protectedTrap.getLocalPort() + "/literal", 1);

            attemptPublisher.publish(attempt.getId());

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
                assertEquals(AttemptStatus.FAILED_RETRYING, reloaded.getStatus());
                assertTrue(reloaded.getLastError().startsWith("DESTINATION_POLICY_BLOCKED: "));
                assertEquals(2, attemptRepository.findAll().size());
            });

            assertEquals(0, resolverInvocations.get(), "protected literals must not invoke DNS");
            assertThrows(SocketTimeoutException.class, protectedTrap::accept,
                    "policy rejection must open no connection to the protected listener");
        }
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

    private static final class WorkerBoundedResponseFixture implements AutoCloseable {

        private static final int BODY_LIMIT = 10_240;
        private static final int READ_SENTINEL = BODY_LIMIT + 1;
        private static final int BODY_LENGTH = 16 * 1024 * 1024;
        private static final int TAIL_CHUNK_SIZE = 16 * 1024;
        private static final String TRUNCATION_MARKER = "\n[relay response truncated at 10240 bytes]";
        private static final String EXPECTED_TRUNCATED_BODY =
                "x".repeat(BODY_LIMIT - TRUNCATION_MARKER.length()) + TRUNCATION_MARKER;

        private final boolean chunked;
        private final int firstStatus;
        private final ServerSocket server;
        private final CountDownLatch firstLimitReached = new CountDownLatch(1);
        private final CountDownLatch allowTail = new CountDownLatch(1);
        private final CountDownLatch tailStarted = new CountDownLatch(1);
        private final CountDownLatch firstClientClosed = new CountDownLatch(1);
        private final CountDownLatch secondServed = new CountDownLatch(1);
        private final AtomicInteger acceptedConnections = new AtomicInteger();
        private final AtomicInteger firstBodyBytesWritten = new AtomicInteger();
        private final AtomicInteger producerBodyBytesWritten = new AtomicInteger();
        private final AtomicBoolean firstProducerFinished = new AtomicBoolean();

        private WorkerBoundedResponseFixture(boolean chunked, int firstStatus) throws IOException {
            this.chunked = chunked;
            this.firstStatus = firstStatus;
            this.server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            Thread.startVirtualThread(this::serve);
        }

        private String url(String path) {
            return "http://bounded.webhook.test:" + server.getLocalPort() + path;
        }

        private void serve() {
            try (Socket first = server.accept()) {
                acceptedConnections.incrementAndGet();
                first.setSendBufferSize(1_024);
                InputStream requestInput = first.getInputStream();
                readHeaders(requestInput);
                OutputStream output = first.getOutputStream();
                writeHeaders(output, firstStatus, chunked ? null : BODY_LENGTH);
                writeGatedBody(output);
                serveReplacement();
            } catch (IOException ignored) {
                firstClientClosed.countDown();
                serveReplacement();
            }
        }

        private void serveReplacement() {
            try (Socket second = server.accept();
                    InputStream requestInput = second.getInputStream();
                    OutputStream output = second.getOutputStream()) {
                acceptedConnections.incrementAndGet();
                readHeaders(requestInput);
                writeHeaders(output, 200, "second response".getBytes(StandardCharsets.UTF_8).length);
                output.write("second response".getBytes(StandardCharsets.UTF_8));
                output.flush();
                secondServed.countDown();
            } catch (IOException ignored) {
                // Fixture teardown.
            }
        }

        private void writeGatedBody(OutputStream output) throws IOException {
            byte[] body = "x".repeat(READ_SENTINEL).getBytes(StandardCharsets.US_ASCII);
            writeApplicationBody(output, body);
            firstBodyBytesWritten.set(body.length);
            producerBodyBytesWritten.set(body.length);
            firstLimitReached.countDown();

            try {
                allowTail.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }

            tailStarted.countDown();
            byte[] tailChunk = "y".repeat(TAIL_CHUNK_SIZE).getBytes(StandardCharsets.US_ASCII);
            int written = body.length;
            while (written < BODY_LENGTH) {
                int count = Math.min(tailChunk.length, BODY_LENGTH - written);
                byte[] next = count == tailChunk.length ? tailChunk : java.util.Arrays.copyOf(tailChunk, count);
                writeApplicationBody(output, next);
                written += count;
                producerBodyBytesWritten.set(written);
            }
            if (chunked) {
                output.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
            }
            firstProducerFinished.set(true);
        }

        private void writeApplicationBody(OutputStream output, byte[] body) throws IOException {
            if (chunked) {
                output.write(Integer.toHexString(body.length).getBytes(StandardCharsets.US_ASCII));
                output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            output.write(body);
            if (chunked) {
                output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            output.flush();
        }

        private void releaseTail() {
            allowTail.countDown();
        }

        private static void readHeaders(InputStream input) throws IOException {
            int matched = 0;
            while (matched < 4) {
                int next = input.read();
                if (next == -1) {
                    return;
                }
                matched = switch (matched) {
                    case 0 -> next == '\r' ? 1 : 0;
                    case 1 -> next == '\n' ? 2 : next == '\r' ? 1 : 0;
                    case 2 -> next == '\r' ? 3 : 0;
                    case 3 -> next == '\n' ? 4 : 0;
                    default -> 4;
                };
            }
        }

        private static void writeHeaders(OutputStream output, int status, Integer contentLength) throws IOException {
            String reason = status >= 500 ? "Internal Server Error" : "OK";
            output.write(("HTTP/1.1 " + status + " " + reason + "\r\n"
                    + (contentLength == null ? "Transfer-Encoding: chunked\r\n"
                            : "Content-Length: " + contentLength + "\r\n")
                    + "Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }

        @Override
        public void close() {
            allowTail.countDown();
            try {
                server.close();
            } catch (IOException ignored) {
                // Fixture teardown is best effort after the worker has closed its exchange.
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LoopbackWebhookTransportConfiguration {

        private static final Set<String> FIXTURE_HOSTS = Set.of(
                "webhook.test.",
                "blocked.webhook.test.",
                "mixed.webhook.test.",
                "bounded.webhook.test.");

        @Bean(name = "loopbackDeliveryConnectionManager", destroyMethod = "close")
        @Primary
        PoolingHttpClientConnectionManager loopbackDeliveryConnectionManager() {
            DeliveryHttpClientConfig config = new DeliveryHttpClientConfig();
            HostAddressLookup lookup = (absoluteHostname, deadline) -> {
                resolverInvocations.incrementAndGet();
                if (!FIXTURE_HOSTS.contains(absoluteHostname)) {
                    throw new DnsResolutionException("unexpected test fixture hostname: " + absoluteHostname);
                }
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
            try (InputStream resource = SpecialPurposeAddressCatalog.class
                    .getResourceAsStream(SpecialPurposeAddressCatalog.RESOURCE)) {
                if (resource == null) {
                    throw new IllegalStateException("missing pinned policy resource");
                }
                String fixtureCatalog = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))
                        .lines()
                        .filter(line -> !line.startsWith("127.0.0.0/8|LOOPBACK")
                                && !line.startsWith("::1/128|LOOPBACK"))
                        .collect(Collectors.joining("\n"));
                return new PublicDestinationAddressPolicy(new SpecialPurposeAddressCatalog(
                        new ByteArrayInputStream(fixtureCatalog.getBytes(StandardCharsets.UTF_8))));
            } catch (IOException exception) {
                throw new IllegalStateException("unable to read pinned policy resource", exception);
            }
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
