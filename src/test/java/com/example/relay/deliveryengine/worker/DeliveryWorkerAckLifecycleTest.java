package com.example.relay.deliveryengine.worker;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.deliveryengine.config.RabbitMqConfig;
import com.example.relay.deliveryengine.http.WebhookDeliveryException;
import com.example.relay.deliveryengine.http.WebhookFailureCode;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import okhttp3.mockwebserver.Dispatcher;
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
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@SpringBootTest
@Testcontainers
@EnableTestBackgroundExecution(TestBackgroundComponent.RABBIT_LISTENERS)
class DeliveryWorkerAckLifecycleTest implements SharedPostgresContainer {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4.3.6-management");

    @Autowired
    private AttemptPublisher attemptPublisher;
    @Autowired
    private AttemptRepository attemptRepository;
    @Autowired
    private DeliveryRepository deliveryRepository;
    @Autowired
    private RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;
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
    private WebhookHttpTransport webhookHttpTransport;

    @MockitoBean
    private HmacSigner hmacSigner;

    private MockWebServer mockWebServer;
    private final HttpClient managementClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        when(hmacSigner.sign(anyString(), anyLong(), any(byte[].class), anyString())).thenReturn("signature");
        when(webhookHttpTransport.post(anyString(), any(byte[].class), any(WebhookHeaders.class)))
                .thenReturn(new WebhookHttpResponse(200, "ok"));
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
        rabbitListenerEndpointRegistry.getListenerContainer("deadLetterNotifier").stop();
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        rabbitListenerEndpointRegistry.getListenerContainer("deadLetterNotifier").start();
        mockWebServer.shutdown();
    }

    private Attempt persistAttempt(String url) {
        return persistAttempt(url, 1);
    }

    private Attempt persistAttempt(String url, int attemptNo) {
        User user = userRepository.save(new User("test" + UUID.randomUUID() + "@mail.com", "hash"));
        Environment env = environmentRepository.save(new Environment("Env", "Desc", user));
        App app = appRepository.save(new App("App", env));
        Event event = eventRepository.save(new Event("payment.completed", app));
        Endpoint endpoint = endpointRepository.save(new Endpoint("EP", url, "whsec_1", app));
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 1);
        Message message = messageRepository.save(new Message(app, event, body));
        Delivery delivery = deliveryRepository.save(new Delivery(app, message, endpoint));
        return attemptRepository.save(new Attempt(app, message, endpoint, delivery, attemptNo));
    }

    private long unacknowledgedCount(String queueName) throws Exception {
        String auth = Base64.getEncoder().encodeToString(
                (rabbitMQContainer.getAdminUsername() + ":" + rabbitMQContainer.getAdminPassword()).getBytes());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(rabbitMQContainer.getHttpUrl() + "/api/queues/%2f/" + queueName))
                .header("Authorization", "Basic " + auth)
                .GET()
                .build();
        HttpResponse<String> response = managementClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = objectMapper.readTree(response.body());
        return json.path("messages_unacknowledged").asLong(-1);
    }

    @Test
    void successfulDelivery_staysUnackedUntilFutureCompletes_thenAcks() throws Exception {
        // The RabbitMQ management API's queue stats (messages_unacknowledged) are refreshed on the
        // broker's own collection interval (~5s by default), not in real time on every request. The
        // mock delivery here must stay in flight for comfortably longer than that interval so the
        // mid-flight await() below has real headroom to observe at least one stats refresh landing
        // while the message is genuinely still unacked, rather than racing a stale/delayed reading.
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            release.await();
            return new WebhookHttpResponse(200, "ok");
        }).when(webhookHttpTransport).post(anyString(), any(byte[].class), any(WebhookHeaders.class));

        Attempt attempt = persistAttempt(mockWebServer.url("/webhook").toString());
        attemptPublisher.publish(attempt.getId());

        // Mid-flight: the HTTP call is still sleeping, so the future hasn't settled - the message
        // must still be unacknowledged on the broker. The await window (1s-9s after publish) covers
        // at least one full ~5s management-stats refresh cycle while delivery is still in flight
        // (it doesn't complete until ~12s), so a stale reading from before publish can't survive the
        // whole window undetected.
        Thread.sleep(1000);
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                assertEquals(1, unacknowledgedCount(RabbitMqConfig.TASKS_QUEUE)));

        release.countDown();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(AttemptStatus.SUCCEEDED, reloaded.getStatus());
        });

        // Post-completion: give the management API at least one full stats-refresh cycle of margin
        // to catch up and report the ack.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(0, unacknowledgedCount(RabbitMqConfig.TASKS_QUEUE)));
    }

    @Test
    void modeledFailure_stillCompletesFutureNormally_andAcks() throws Exception {
        when(webhookHttpTransport.post(anyString(), any(byte[].class), any(WebhookHeaders.class)))
                .thenReturn(new WebhookHttpResponse(500, "internal error"));
        Attempt attempt = persistAttempt(mockWebServer.url("/webhook").toString());
        attemptPublisher.publish(attempt.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(AttemptStatus.FAILED_RETRYING, reloaded.getStatus());
        });

        // Same management-API stats-refresh headroom as the successful-delivery test above.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(0, unacknowledgedCount(RabbitMqConfig.TASKS_QUEUE)));
    }

    @Test
    void workerPassesSameAuthoritativeBodyReferenceToSignerAndTransport() throws Exception {
        AtomicReference<byte[]> signedBody = new AtomicReference<>();
        AtomicReference<byte[]> transportedBody = new AtomicReference<>();
        doAnswer(invocation -> {
            signedBody.set(invocation.getArgument(2));
            return "signature";
        }).when(hmacSigner).sign(anyString(), anyLong(), any(byte[].class), anyString());
        doAnswer(invocation -> {
            transportedBody.set(invocation.getArgument(1));
            return new WebhookHttpResponse(200, "ok");
        }).when(webhookHttpTransport).post(anyString(), any(byte[].class), any(WebhookHeaders.class));

        Attempt attempt = persistAttempt("http://webhook.test/webhook");
        attemptPublisher.publish(attempt.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(AttemptStatus.SUCCEEDED,
                        attemptRepository.findById(attempt.getId()).orElseThrow().getStatus()));
        assertSame(signedBody.get(), transportedBody.get());
    }

    @Test
    void modeledTransportFailure_isPersistedWithStableBoundedCodeBeforeAcknowledgment() throws Exception {
        doThrow(new WebhookDeliveryException(WebhookFailureCode.TRANSPORT_FAILURE, "x".repeat(2_000)))
                .when(webhookHttpTransport)
                .post(anyString(), any(byte[].class), any(WebhookHeaders.class));
        Attempt attempt = persistAttempt(mockWebServer.url("/webhook").toString());
        attemptPublisher.publish(attempt.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(AttemptStatus.FAILED_RETRYING, reloaded.getStatus());
            assertTrue(reloaded.getLastError().startsWith("TRANSPORT_FAILURE: "));
            assertTrue(reloaded.getLastError().length() <= 10_240);
            assertTrue(reloaded.getStatus() != AttemptStatus.IN_FLIGHT);
        });

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(0, unacknowledgedCount(RabbitMqConfig.TASKS_QUEUE)));
    }

    static Stream<Arguments> modeledFailureCases() {
        List<WebhookFailureCode> codes = List.of(WebhookFailureCode.DESTINATION_INVALID,
                WebhookFailureCode.DNS_RESOLUTION_FAILED, WebhookFailureCode.DESTINATION_POLICY_BLOCKED,
                WebhookFailureCode.DELIVERY_TIMEOUT, WebhookFailureCode.TRANSPORT_FAILURE);
        return codes.stream().flatMap(code -> IntStream.rangeClosed(1, 6)
                .mapToObj(attemptNo -> Arguments.of(code, attemptNo)));
    }

    @ParameterizedTest(name = "{0} on attempt {1} follows existing lifecycle")
    @MethodSource("modeledFailureCases")
    void eachModeledFailureCode_usesRetryThenDeadLifecycle(WebhookFailureCode code, int attemptNo) throws Exception {
        doThrow(new WebhookDeliveryException(code, "diagnostic-".repeat(1_000)))
                .when(webhookHttpTransport)
                .post(anyString(), any(byte[].class), any(WebhookHeaders.class));
        Attempt attempt = persistAttempt(mockWebServer.url("/webhook").toString(), attemptNo);
        attemptPublisher.publish(attempt.getId());

        AttemptStatus expected = attemptNo == 6 ? AttemptStatus.DEAD : AttemptStatus.FAILED_RETRYING;
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(expected, reloaded.getStatus());
            assertTrue(reloaded.getLastError().startsWith(code + ": "));
            assertTrue(reloaded.getLastError().length() <= 10_240);
        });
        List<Attempt> attempts = attemptRepository.findAll();
        assertEquals(attemptNo == 6 ? 1 : 2, attempts.size());
        if (attemptNo < 6) {
            Attempt retry = attempts.stream()
                    .filter(candidate -> !candidate.getId().equals(attempt.getId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(attemptNo + 1, retry.getAttemptNo());
            assertEquals(AttemptStatus.SCHEDULED, retry.getStatus());
            assertTrue(retry.getNextRetryAt() != null);
        }
    }
}
