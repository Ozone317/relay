package com.example.relay.delivery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.exception.ActiveAttemptAlreadyExistsException;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves that idx_attempts_one_active_per_message_endpoint - not the app-level
 * existsByMessageIdAndEndpointIdAndStatusIn pre-check - is what actually stops a concurrent double
 * replay. The pre-check is only a fast path; two threads that both pass it and race into
 * AttemptService.createReplay can only be arbitrated by the real DB constraint.
 *
 * <p>
 * Ported from the pre-Task-8 AttemptReplayConcurrencyPostgresTest onto DeliveryReplayService's
 * delivery-scoped API (replay(UUID deliveryId, ...): DeliveryStatus instead of replay(UUID
 * attemptId, ...): Attempt) after AttemptReplayService was deleted as dead code.
 */
@SpringBootTest
class DeliveryReplayConcurrencyPostgresTest implements SharedPostgresContainer {

    @Autowired
    private DeliveryReplayService deliveryReplayService;
    @Autowired
    private AttemptRepository attemptRepository;
    @Autowired
    private DeliveryRepository deliveryRepository;
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

    private UUID environmentId;
    private UUID appId;
    private UUID userId;
    private Delivery delivery;

    @BeforeEach
    void setUp() throws Exception {
        attemptRepository.deleteAll();
        deliveryRepository.deleteAll();
        messageRepository.deleteAll();
        endpointRepository.deleteAll();
        eventRepository.deleteAll();
        appRepository.deleteAll();
        environmentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        User user = userRepository.save(new User("replay-" + UUID.randomUUID() + "@mail.com", "hash"));
        Environment env = environmentRepository.save(new Environment("Env 1", "Desc 1", user));
        App app = appRepository.save(new App("App 1", env));
        Event event = eventRepository.save(new Event("payment.completed", app));
        Endpoint endpoint = endpointRepository
                .save(new Endpoint("EP 1", "https://example.com/webhook", "whsec_1", app));
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        Message message = messageRepository.save(new Message(app, event, body));

        delivery = deliveryRepository.save(new Delivery(app, message, endpoint));
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 6);
        attempt.setStatus(AttemptStatus.DEAD);
        attemptRepository.save(attempt);

        environmentId = env.getId();
        appId = app.getId();
        userId = user.getId();
    }

    @Test
    void twoConcurrentReplays_exactlyOneSucceeds() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        List<Future<?>> futures = List.of(
                executor.submit(() -> race(readyLatch, startLatch, successCount, conflictCount)),
                executor.submit(() -> race(readyLatch, startLatch, successCount, conflictCount)));

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS),
                "both replay threads should reach the rendezvous point within 5 seconds");
        startLatch.countDown();

        for (Future<?> future : futures) {
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        executor.shutdown();

        assertEquals(1, successCount.get(), "exactly one concurrent replay should succeed");
        assertEquals(1, conflictCount.get(), "the other concurrent replay should be rejected as a conflict");
    }

    private void race(CountDownLatch readyLatch, CountDownLatch startLatch, AtomicInteger successCount,
            AtomicInteger conflictCount) {
        try {
            readyLatch.countDown();
            startLatch.await();
            deliveryReplayService.replay(delivery.getId(), appId, environmentId, userId);
            successCount.incrementAndGet();
        } catch (ActiveAttemptAlreadyExistsException e) {
            conflictCount.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
