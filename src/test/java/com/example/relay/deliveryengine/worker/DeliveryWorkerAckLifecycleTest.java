package com.example.relay.deliveryengine.worker;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.deliveryengine.config.RabbitMqConfig;
import com.example.relay.deliveryengine.publisher.AttemptPublisher;
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
import java.util.UUID;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class DeliveryWorkerAckLifecycleTest implements SharedPostgresContainer {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4-management");

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
    private final HttpClient managementClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        attemptRepository.deleteAll();
        deliveryRepository.deleteAll();
        messageRepository.deleteAll();
        endpointRepository.deleteAll();
        eventRepository.deleteAll();
        appRepository.deleteAll();
        environmentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
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
        User user = userRepository.save(new User("test" + UUID.randomUUID() + "@mail.com", "hash"));
        Environment env = environmentRepository.save(new Environment("Env", "Desc", user));
        App app = appRepository.save(new App("App", env));
        Event event = eventRepository.save(new Event("payment.completed", app));
        Endpoint endpoint = endpointRepository.save(new Endpoint("EP", url, "whsec_1", app));
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 1);
        Message message = messageRepository.save(new Message(app, event, body));
        Delivery delivery = deliveryRepository.save(new Delivery(app, message, endpoint));
        return attemptRepository.save(new Attempt(app, message, endpoint, delivery, 1));
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
        mockWebServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                Thread.sleep(12000);
                return new MockResponse().setResponseCode(200).setBody("ok");
            }
        });

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
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("internal error"));
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
}
