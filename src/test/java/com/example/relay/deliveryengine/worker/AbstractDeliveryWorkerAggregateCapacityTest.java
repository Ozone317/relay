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
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Two independent splits (4x10 in {@link DeliveryWorkerAggregateCapacityFourByTenTest}, 1x40 in
 * {@link DeliveryWorkerAggregateCapacityOneByFortyTest}) that both multiply to the same ~40-in-flight
 * aggregate ceiling - spec Sec.6 tests 1 and 2. Each split needs its own Spring context (properties
 * are fixed at context creation), hence two subclasses with distinct {@code @DynamicPropertySource}
 * overrides rather than one parameterized test. This class holds the shared fixture and both
 * {@code @Test} bodies so the two splits can't drift apart from each other.
 */
abstract class AbstractDeliveryWorkerAggregateCapacityTest implements SharedPostgresContainer {

    protected abstract int consumerConcurrency();
    protected abstract int prefetchCount();

    private int aggregateCeiling() {
        return consumerConcurrency() * prefetchCount();
    }

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
    void backpressureProof_admitsUpToCeiling_thenTheNextOneWaitsUntilOneSlotIsReleased() throws Exception {
        // The precise protocol (not just "observe a peak concurrency number") that actually proves
        // prefetch is the backpressure mechanism:
        //   1. Arrange `ceiling` messages whose HTTP calls block at the server.
        //   2. Publish them.
        //   3. Confirm approximately `ceiling` are admitted/in HTTP processing concurrently.
        //   4. Publish one more.
        //   5. While the first `ceiling` remain blocked, confirm the extra one does NOT reach the
        //      HTTP server.
        //   6. Release exactly one of the first `ceiling`.
        //   7. Confirm the extra one is now delivered, only after that release.
        int ceiling = aggregateCeiling();
        Semaphore releaseGate = new Semaphore(0);
        // `arrived` is an APPLICATION-LEVEL observation - it counts requests that reached
        // MockWebServer's dispatcher, i.e. that a virtual thread got far enough to make the
        // outbound HTTP call. It is evidence of the delivery/HTTP concurrency boundary, not a
        // direct measurement of RabbitMQ's own internal unacknowledged-message counter - for that,
        // see Task 3's DeliveryWorkerAckLifecycleTest, which queries the real management API.
        AtomicInteger arrived = new AtomicInteger(0);

        mockWebServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                arrived.incrementAndGet();
                releaseGate.acquire(); // blocks this request at the server until explicitly released
                return new MockResponse().setResponseCode(200).setBody("ok");
            }
        });

        // Steps 1-2: arrange and publish `ceiling` blocked deliveries.
        for (int i = 0; i < ceiling; i++) {
            Attempt attempt = persistAttempt(mockWebServer.url("/webhook").toString());
            attemptPublisher.publish(attempt.getId());
        }

        // Step 3: confirm approximately `ceiling` have reached HTTP processing.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertEquals(ceiling, arrived.get()));

        // Step 4: publish one more.
        Attempt extra = persistAttempt(mockWebServer.url("/webhook").toString());
        attemptPublisher.publish(extra.getId());

        // Step 5: while the first `ceiling` remain blocked, confirm the extra one has NOT reached
        // the server - a generous window, not an instant check, since we're proving absence.
        Thread.sleep(2000);
        assertEquals(ceiling, arrived.get(),
                "expected message " + (ceiling + 1) + " to NOT reach the HTTP server while all " + ceiling
                        + " already-admitted deliveries are still held - if this reads " + (ceiling + 1)
                        + ", prefetch is not actually providing backpressure");

        // Step 6: release exactly one of the first `ceiling`.
        releaseGate.release(1);

        // Step 7: confirm the extra one now reaches the HTTP server, only after that release.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(ceiling + 1, arrived.get()));

        // Cleanup: release every remaining held request so all attempts settle to SUCCEEDED and no
        // blocked dispatch threads leak into the next test.
        releaseGate.release(ceiling);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            long succeeded = attemptRepository.findAll().stream()
                    .filter(a -> a.getStatus() == AttemptStatus.SUCCEEDED).count();
            assertEquals(ceiling + 1, succeeded);
        });
    }

    @Test
    void nonVacuousParallelism_wallClockTracksCeilingNotTotalCount() {
        // Spec Sec.6 test 8: N delayed deliveries should complete in time proportional to
        // ceil(N / ceiling) * delay, not N * delay - proving real parallelism happened rather than
        // just asserting the config took effect.
        int ceiling = aggregateCeiling();
        int delayMs = 1000;
        int totalMessages = ceiling * 2; // exactly two "waves" at the ceiling

        mockWebServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                Thread.sleep(delayMs);
                return new MockResponse().setResponseCode(200).setBody("ok");
            }
        });

        Instant started = Instant.now();
        for (int i = 0; i < totalMessages; i++) {
            Attempt attempt = persistAttempt(mockWebServer.url("/webhook").toString());
            attemptPublisher.publish(attempt.getId());
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            long succeeded = attemptRepository.findAll().stream()
                    .filter(a -> a.getStatus() == AttemptStatus.SUCCEEDED).count();
            assertEquals(totalMessages, succeeded);
        });
        Duration elapsed = Duration.between(started, Instant.now());

        long expectedWavesMs = 2L * delayMs; // 2 waves at the ceiling
        long serialMs = (long) totalMessages * delayMs; // what it would take with zero parallelism

        assertTrue(elapsed.toMillis() < serialMs / 2,
                "expected real parallelism: " + totalMessages + " x " + delayMs + "ms deliveries took "
                        + elapsed.toMillis() + "ms, which is not meaningfully faster than the fully-serial "
                        + serialMs + "ms - parallelism may not actually be happening");
        assertTrue(elapsed.toMillis() < expectedWavesMs * 4,
                "expected roughly " + expectedWavesMs + "ms (2 waves at the ceiling) with generous slack; took "
                        + elapsed.toMillis() + "ms");
    }
}
