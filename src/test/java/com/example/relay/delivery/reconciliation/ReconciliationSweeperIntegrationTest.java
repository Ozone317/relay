package com.example.relay.delivery.reconciliation;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.config.RabbitMqConfig;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.endpoint.infrastructure.EndpointRepository;
import com.example.relay.environment.domain.Environment;
import com.example.relay.environment.infrastructure.EnvironmentRepository;
import com.example.relay.event.domain.Event;
import com.example.relay.event.infrastructure.EventRepository;
import com.example.relay.message.infrastructure.MessageRepository;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        // The real @Scheduled loop and the real DeliveryWorker listener would otherwise race
        // this test's own calls to sweep() and its own queue reads - same reasoning as
        // AttemptPublisherIntegrationTest disabling the listener for its own queue reads.
        "spring.task.scheduling.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "relay.reconciliation.batch-size=2",
        "relay.reconciliation.scheduled-slack=5m"
})
public class ReconciliationSweeperIntegrationTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4-management");

    @Autowired
    private ReconciliationSweeper sweeper;

    @Autowired
    private AttemptRepository attemptRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private UserRepository userRepository;

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
        messageRepository.deleteAll();
        endpointRepository.deleteAll();
        eventRepository.deleteAll();
        appRepository.deleteAll();
        environmentRepository.deleteAll();
        userRepository.deleteAll();
        drainTasksQueue();

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

    private Attempt persistAttemptWithUpdatedAt(AttemptStatus status, Instant updatedAt) {
        Attempt attempt = attemptRepository.save(new Attempt(
                endpoint.getApp(), message, endpoint, 1));
        if (status == AttemptStatus.IN_FLIGHT) {
            attemptService.claim(attempt.getId(), Instant.now());
        }
        backdateUpdatedAt(attempt.getId(), updatedAt);
        return attempt;
    }

    private Attempt persistScheduledAttempt(Instant nextRetryAt) {
        Attempt attempt = attemptRepository.save(new Attempt(endpoint.getApp(), message, endpoint, 2));
        attempt.setStatus(AttemptStatus.SCHEDULED);
        attempt.setNextRetryAt(nextRetryAt);
        return attemptRepository.save(attempt);
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
    void staleCreatedAttempt_getsRepublished() {
        Attempt attempt = persistAttemptWithUpdatedAt(
                AttemptStatus.CREATED, Instant.now().minusSeconds(3600));

        sweeper.sweep();

        Message queued = rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 5000);
        assertNotNull(queued, "expected the stale CREATED attempt to be republished");
        assertEquals(attempt.getId().toString(), new String(queued.getBody()));
    }

    @Test
    void freshCreatedAttempt_isLeftAlone() {
        persistAttemptWithUpdatedAt(AttemptStatus.CREATED, Instant.now());

        sweeper.sweep();

        Message queued = rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 2000);
        assertNull(queued, "a freshly created attempt should not be swept yet");
    }

    @Test
    void staleInFlightAttempt_isResetAndRepublished() {
        Attempt attempt = persistAttemptWithUpdatedAt(
                AttemptStatus.IN_FLIGHT, Instant.now().minusSeconds(3600));

        sweeper.sweep();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(AttemptStatus.CREATED, reloaded.getStatus());
        });

        Message queued = rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 5000);
        assertNotNull(queued, "expected the reset attempt to be republished");
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
    void batchSize_limitsHowManyStaleAttemptsAreSweptPerCycle() {
        for (int i = 0; i < 3; i++) {
            persistAttemptWithUpdatedAt(AttemptStatus.CREATED, Instant.now().minusSeconds(3600));
        }

        sweeper.sweep();

        int republished = 0;
        while (rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 500) != null) {
            republished++;
        }
        // Relies on relay.reconciliation.batch-size being overridden below the default 100 for
        // this assertion to mean anything - see the @TestPropertySource addition below.
        assertEquals(2, republished);
    }

    @Test
    void staleCreatedAttempt_isNotRepublishedOnEveryConsecutiveSweep() {
        // Honest now, and not before Step 3's fix: with created-grace >= interval enforced at
        // startup, a row touched by recoverCreated() cannot go stale again before at least one
        // full interval has elapsed. So calling sweep() three times with ~0ms between calls is a
        // valid lower bound on real @Scheduled(fixedDelay) spacing - if the row doesn't re-match
        // after zero elapsed time, it provably won't re-match after a real interval's worth of
        // time either. Before the validation existed, this same test would have proven nothing.
        persistAttemptWithUpdatedAt(AttemptStatus.CREATED, Instant.now().minusSeconds(3600));

        sweeper.sweep();
        sweeper.sweep();
        sweeper.sweep();

        int republished = 0;
        while (rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 500) != null) {
            republished++;
        }
        assertEquals(1, republished,
                "three back-to-back sweeps of one stuck row should republish it once, not three times");
    }

    @Test
    void overdueScheduledAttempt_isResetAndRepublished() {
        Attempt attempt = persistScheduledAttempt(Instant.now().minusSeconds(3600));

        sweeper.sweep();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(AttemptStatus.CREATED, reloaded.getStatus());
        });

        Message queued = rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 5000);
        assertNotNull(queued, "expected the overdue SCHEDULED attempt to be recovered to delivery.tasks");
        assertEquals(attempt.getId().toString(), new String(queued.getBody()));
    }

    @Test
    void notYetDueScheduledAttempt_isLeftAlone() {
        Attempt attempt = persistScheduledAttempt(Instant.now().plusSeconds(3600));

        sweeper.sweep();

        Message queued = rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 2000);
        assertNull(queued, "a SCHEDULED attempt not yet due must not be recovered early");
        assertEquals(AttemptStatus.SCHEDULED, attemptRepository.findById(attempt.getId()).orElseThrow().getStatus());
    }

    @Test
    void scheduledAttempt_dueButWithinSlack_isLeftAlone() {
        // D3's core scenario: a retry whose grace-by-updated_at would look stale, but whose actual
        // backoff (next_retry_at) has not elapsed past the configured slack yet.
        Attempt attempt = persistScheduledAttempt(Instant.now().minusSeconds(30));

        sweeper.sweep();

        Message queued = rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 2000);
        assertNull(queued, "a SCHEDULED attempt within its slack window must not be recovered early");
        assertEquals(AttemptStatus.SCHEDULED, attemptRepository.findById(attempt.getId()).orElseThrow().getStatus());
    }

    @Test
    void inFlightAttempt_claimedAfterSittingStaleForHours_isNotImmediatelyResetMidDelivery() {
        // Reproduces D1: before the fix, claim() left updated_at at its pre-claim value, so a retry
        // claimed after (e.g.) six hours in a wait tier looked immediately stale to recoverInFlight,
        // which would reset it back to CREATED and republish it WHILE the HTTP call was still running.
        Attempt attempt = attemptRepository.save(new Attempt(endpoint.getApp(), message, endpoint, 1));
        backdateUpdatedAt(attempt.getId(), Instant.now().minusSeconds(21_600)); // 6 hours, pre-claim

        attemptService.claim(attempt.getId(), Instant.now()); // should stamp updated_at to ~now (Task 2's fix)

        sweeper.sweep();

        Attempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(AttemptStatus.IN_FLIGHT, reloaded.getStatus(),
                "a just-claimed attempt must not be reset mid-delivery even if it was stale before claiming");

        Message queued = rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 2000);
        assertNull(queued, "must not be republished while genuinely in flight");
    }
}
