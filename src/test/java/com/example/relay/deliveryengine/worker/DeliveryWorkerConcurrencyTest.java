package com.example.relay.deliveryengine.worker;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
class DeliveryWorkerConcurrencyTest implements SharedPostgresContainer {

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

    @Test
    void consumerConcurrencyAlone_yieldsRealParallelHttpCalls() throws InterruptedException {
        // Everything responds after a 3s delay. With consumer-concurrency=4 (the configured
        // default) and 4 attempts published, all 4 should be received by the mock server within a
        // tight window of each other - proving 4 genuinely separate consumer threads, not one
        // thread working through them serially (which would space the arrivals ~3s apart).
        mockWebServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                Thread.sleep(3000);
                return new MockResponse().setResponseCode(200).setBody("ok");
            }
        });

        for (int i = 0; i < 4; i++) {
            Attempt attempt = persistAttempt(mockWebServer.url("/webhook").toString());
            attemptPublisher.publish(attempt.getId());
        }

        long firstRequestNanos = System.nanoTime();
        mockWebServer.takeRequest(10, TimeUnit.SECONDS);
        for (int i = 0; i < 3; i++) {
            RecordedRequest request = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
            assertTrue(request != null,
                    "expected all 4 requests to arrive within ~2s of each other (real parallelism), "
                            + "not serially ~3s apart");
        }
        long arrivalWindowMs = Duration.ofNanos(System.nanoTime() - firstRequestNanos).toMillis();
        assertTrue(arrivalWindowMs < 2500,
                "expected all 4 slow requests to be dispatched within 2.5s of each other; took "
                        + arrivalWindowMs + "ms, which looks serial not parallel");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            long succeeded = attemptRepository.findAll().stream()
                    .filter(a -> a.getStatus() == AttemptStatus.SUCCEEDED)
                    .count();
            assertEquals(4, succeeded);
        });
    }

    @Test
    void deliveryWorkerListenerContainer_isRegisteredUnderExplicitId() {
        assertTrue(rabbitListenerEndpointRegistry.getListenerContainer("deliveryWorker") != null,
                "expected a listener container registered under id 'deliveryWorker'");
    }
}
