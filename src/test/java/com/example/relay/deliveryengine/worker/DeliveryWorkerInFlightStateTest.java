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
import java.util.concurrent.Semaphore;
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
class DeliveryWorkerInFlightStateTest implements SharedPostgresContainer {

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
    void attemptStaysGenuinelyInFlight_whileItsDeliveryTaskIsStillRunning_thenCompletesNormallyOnceReleased()
            throws Exception {
        Semaphore releaseGate = new Semaphore(0);
        mockWebServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                releaseGate.acquire();
                return new MockResponse().setResponseCode(200).setBody("ok");
            }
        });

        Attempt attempt = persistAttempt(mockWebServer.url("/webhook").toString());
        attemptPublisher.publish(attempt.getId());

        // While the HTTP call is deliberately held open (the task is still running, not crashed and
        // not thrown), confirm claim() already committed the row to IN_FLIGHT - this is the exact
        // precondition ReconciliationSweeper.recoverInFlight (unchanged, separately tested in
        // ReconciliationSweeperIntegrationTest) depends on seeing for a row that's genuinely stuck.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(AttemptStatus.IN_FLIGHT, reloaded.getStatus());
        });

        // Release the held HTTP call and confirm the attempt completes normally - proving the async
        // hand-off doesn't leave the row stuck once the task actually finishes; only a real crash
        // (which this test does not attempt to simulate, per spec Sec.5) would leave it stuck.
        releaseGate.release(1);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(AttemptStatus.SUCCEEDED, reloaded.getStatus());
        });
    }
}
