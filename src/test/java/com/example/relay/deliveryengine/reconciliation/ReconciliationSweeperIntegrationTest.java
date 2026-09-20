package com.example.relay.deliveryengine.reconciliation;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.relay.app.domain.App;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.deliveryengine.config.RabbitMqConfig;
import com.example.relay.deliveryengine.dispatcher.ReadyWorkDispatcher;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.endpoint.infrastructure.EndpointRepository;
import com.example.relay.environment.domain.Environment;
import com.example.relay.environment.infrastructure.EnvironmentRepository;
import com.example.relay.event.domain.Event;
import com.example.relay.event.infrastructure.EventRepository;
import com.example.relay.message.infrastructure.MessageRepository;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Tag("integration")
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        // The real @Scheduled loop and the real DeliveryWorker listener would otherwise race
        // this test's own calls to sweep() and its own queue reads - same reasoning as
        // AttemptPublisherIntegrationTest disabling the listener for its own queue reads.
        "spring.task.scheduling.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "relay.retry.scheduling-enabled=false",
        "relay.reconciliation.scheduling-enabled=false",
        "relay.reconciliation.batch-size=2",
        "relay.reconciliation.interval=1h",
        "relay.reconciliation.dead-letter-grace=1h",
        "relay.retry.scheduler-interval=1h",
        "relay.retry.dispatcher-interval=1h"
})
public class ReconciliationSweeperIntegrationTest implements SharedPostgresContainer {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4.3.6-management");

    @Autowired
    private ReconciliationSweeper sweeper;

    @Autowired
    private ReadyWorkDispatcher readyWorkDispatcher;

    @Autowired
    private AttemptRepository attemptRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

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

    @Autowired
    private AttemptService attemptService;

    @PersistenceContext
    private EntityManager entityManager;

    private Endpoint endpoint;
    private com.example.relay.message.domain.Message message;

    @BeforeEach
    void setUp() {
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
        drainTasksQueue();
        drainDeadletterQueue();

        User user = userRepository.save(new User("test" + UUID.randomUUID() + "@mail.com", "hash"));
        Environment env = environmentRepository.save(new Environment("Env 1", "Desc 1", user));
        App app = appRepository.save(new App("App 1", env));
        Event event = eventRepository.save(new Event("payment.completed", app));
        endpoint = endpointRepository.save(new Endpoint("EP 1", "https://example.com/webhook", "whsec_1", app));
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        message = messageRepository.save(new com.example.relay.message.domain.Message(app, event, body));
    }

    private void drainTasksQueue() {
        while (rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 100) != null) {
            // discard leftover messages from a prior test
        }
    }

    private void drainDeadletterQueue() {
        while (rabbitTemplate.receive(RabbitMqConfig.DEADLETTER_QUEUE, 100) != null) {
            // discard leftover messages from a prior test
        }
    }

    private Attempt persistAttemptWithUpdatedAt(AttemptStatus status, Instant updatedAt) {
        Delivery delivery = deliveryRepository.save(new Delivery(endpoint.getApp(), message, endpoint));
        Attempt attempt = attemptRepository.save(new Attempt(
                endpoint.getApp(), message, endpoint, delivery, 1));
        if (status == AttemptStatus.IN_FLIGHT) {
            attemptService.claim(attempt.getId(), Instant.now());
        }
        backdateUpdatedAt(attempt.getId(), updatedAt);
        return attempt;
    }

    private Attempt persistDeadAttempt(Instant updatedAt, Instant deadLetterNotifiedAt) {
        Delivery delivery = deliveryRepository.save(new Delivery(endpoint.getApp(), message, endpoint));
        Attempt attempt = attemptRepository.save(new Attempt(endpoint.getApp(), message, endpoint, delivery, 6));
        attempt.setStatus(AttemptStatus.DEAD);
        attempt.setDeadLetterNotifiedAt(deadLetterNotifiedAt);
        attempt = attemptRepository.save(attempt);
        backdateUpdatedAt(attempt.getId(), updatedAt);
        return attempt;
    }

    private void backdateUpdatedAt(UUID id, Instant timestamp) {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createQuery("""
                UPDATE Attempt a
                SET a.updatedAt = :updatedAt
                WHERE a.id = :id
            """)
            .setParameter("updatedAt", timestamp)
            .setParameter("id", id)
            .executeUpdate();
        });
    }

    @Test
    void staleInFlightAttempt_isResetWithoutDirectPublication() {
        Attempt attempt = persistAttemptWithUpdatedAt(
                AttemptStatus.IN_FLIGHT, Instant.now().minusSeconds(3600));

        sweeper.sweep();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(AttemptStatus.CREATED, reloaded.getStatus());
            assertNull(reloaded.getReadyPublishedAt());
            assertNull(reloaded.getReadyDispatchClaimId());
            assertNull(reloaded.getReadyDispatchClaimedAt());
        });

        // A dispatcher may already have published the reset CREATED row by this point. The
        // focused scheduling test proves that the sweeper never calls AttemptPublisher directly;
        // this integration test proves the dispatcher-owned task publication against RabbitMQ.
        readyWorkDispatcher.dispatchOnce();
        Message queued = rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 5000);
        assertNotNull(queued, "dispatcher must publish reset CREATED work");
        assertEquals(attempt.getId().toString(), new String(queued.getBody()));
    }

    @Test
    void freshInFlightAttempt_isLeftAlone() {
        Attempt attempt = persistAttemptWithUpdatedAt(AttemptStatus.IN_FLIGHT, Instant.now());

        sweeper.sweep();

        Message queued = rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 2000);
        assertNull(queued, "a freshly claimed attempt should not be swept yet");
        assertEquals(AttemptStatus.IN_FLIGHT, attemptRepository.findById(attempt.getId()).orElseThrow().getStatus());
    }

    @Test
    void inFlightAttempt_thatFinishesConcurrently_isNotResetOrDuplicated() {
        // Simulates DeliveryWorker completing the delivery in the gap between the sweeper's
        // SELECT and its UPDATE: the row is stale by updated_at at query time, but by the time
        // resetStuck's own WHERE clause re-evaluates it, it's already SUCCEEDED with a fresh
        // updated_at. Proven the same way RELAY_HANDOFF.md documents proving the poison-message
        // fix: by directly asserting the guarded outcome, not by racing real threads.
        Attempt attempt = persistAttemptWithUpdatedAt(
                AttemptStatus.IN_FLIGHT, Instant.now().minusSeconds(3600));

        attempt.setStatus(AttemptStatus.SUCCEEDED);
        attemptRepository.save(attempt); // bumps updated_at to "now" via @UpdateTimestamp

        sweeper.sweep();

        Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(AttemptStatus.SUCCEEDED, reloaded.getStatus());

        Message queued = rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 2000);
        assertNull(queued, "a concurrently-completed attempt must not be republished");
    }

    @Test
    void inFlightAttempt_claimedAfterSittingStaleForHours_isNotImmediatelyResetMidDelivery() {
        // Reproduces D1: before the fix, claim() left updated_at at its pre-claim value, so a retry
        // claimed after (e.g.) six hours in a wait tier looked immediately stale to recoverInFlight,
        // which would reset it back to CREATED and republish it WHILE the HTTP call was still running.
        Delivery delivery = deliveryRepository.save(new Delivery(endpoint.getApp(), message, endpoint));
        Attempt attempt = attemptRepository.save(new Attempt(endpoint.getApp(), message, endpoint, delivery, 1));
        backdateUpdatedAt(attempt.getId(), Instant.now().minusSeconds(21_600)); // 6 hours, pre-claim

        attemptService.claim(attempt.getId(), Instant.now()); // should stamp updated_at to ~now (Task 2's fix)

        sweeper.sweep();

        Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(AttemptStatus.IN_FLIGHT, reloaded.getStatus(),
                "a just-claimed attempt must not be reset mid-delivery even if it was stale before claiming");

        Message queued = rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 2000);
        assertNull(queued, "must not be republished while genuinely in flight");
    }

    @Test
    void staleUnnotifiedDeadAttempt_getsRepublishedToDeadletterQueue() {
        Attempt attempt = persistDeadAttempt(Instant.now().minusSeconds(3600), null);

        sweeper.sweep();

        Message queued = rabbitTemplate.receive(RabbitMqConfig.DEADLETTER_QUEUE, 5000);
        assertNotNull(queued, "expected the stale unnotified DEAD attempt to be republished");
        assertEquals(attempt.getId().toString(), new String(queued.getBody()));
    }

    @Test
    void staleButAlreadyNotifiedDeadAttempt_isLeftAlone() {
        persistDeadAttempt(Instant.now().minusSeconds(3600), Instant.now());

        sweeper.sweep();

        Message queued = rabbitTemplate.receive(RabbitMqConfig.DEADLETTER_QUEUE, 2000);
        assertNull(queued, "an already-notified DEAD attempt must not be republished");
    }

    @Test
    void freshUnnotifiedDeadAttempt_isLeftAlone() {
        persistDeadAttempt(Instant.now(), null);

        sweeper.sweep();

        Message queued = rabbitTemplate.receive(RabbitMqConfig.DEADLETTER_QUEUE, 2000);
        assertNull(queued, "a freshly dead-lettered attempt should not be swept yet");
    }

    @Test
    void staleUnnotifiedDeadAttempt_isNotRepublishedOnEveryConsecutiveSweep() {
        // The same D2-shaped guard is proven here for dead-letter recovery, since
        // relay.reconciliation.dead-letter-grace >= interval is enforced at startup (Step 9).
        persistDeadAttempt(Instant.now().minusSeconds(3600), null);

        sweeper.sweep();
        sweeper.sweep();
        sweeper.sweep();

        int republished = 0;
        while (rabbitTemplate.receive(RabbitMqConfig.DEADLETTER_QUEUE, 500) != null) {
            republished++;
        }
        assertEquals(1, republished,
                "three back-to-back sweeps of one stuck DEAD row should republish it once, not three times");
    }
}
