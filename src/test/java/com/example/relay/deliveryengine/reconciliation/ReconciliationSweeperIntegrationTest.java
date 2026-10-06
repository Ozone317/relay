package com.example.relay.deliveryengine.reconciliation;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.attempt.infrastructure.AttemptAllocationRepository;
import com.example.relay.attempt.infrastructure.AttemptExecutionRepository;
import com.example.relay.attempt.infrastructure.AttemptExecutionRepositoryImpl;
import com.example.relay.attempt.infrastructure.AttemptExecutionClaim;
import com.example.relay.attempt.application.AttemptExecution;
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
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.support.ScheduledCallbackTestSupport;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
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

@Tag("integration")
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {"relay.reconciliation.batch-size=2", "relay.reconciliation.interval=1h",
        "relay.reconciliation.dead-letter-grace=1h", "relay.retry.scheduler-interval=1h",
        "relay.retry.dispatcher-interval=1h"})
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
    private AttemptExecutionRepository executionRepository;

    @Autowired
    private AttemptExecutionRepositoryImpl executionRepositoryDelegate;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private AttemptAllocationRepository allocationRepository;

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

    @Autowired
    private com.example.relay.deliveryengine.publisher.AttemptPublisher attemptPublisher;

    @Autowired
    private ReconciliationProperties reconciliationProperties;

    @Autowired
    private com.example.relay.deliveryengine.worker.ExecutionOwnershipMetrics executionOwnershipMetrics;

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
        Attempt attempt = attemptRepository.save(new Attempt(endpoint.getApp(), message, endpoint, delivery, 1));
        if (status == AttemptStatus.IN_FLIGHT) {
            attemptService.claim(attempt.getId());
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
                    """).setParameter("updatedAt", timestamp).setParameter("id", id).executeUpdate();
        });
    }

    private void backdateClaim(UUID id) {
        transactionTemplate.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE attempts SET execution_claimed_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE id = :id")
                .setParameter("id", id).executeUpdate());
    }

    @Test
    void staleInFlightAttempt_isResetWithoutDirectPublication() {
        Attempt attempt = persistAttemptWithUpdatedAt(AttemptStatus.IN_FLIGHT, Instant.now());
        backdateClaim(attempt.getId());

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
    void staleObservedGeneration_cannotResetNewOwner() {
        Attempt attempt = persistAttemptWithUpdatedAt(AttemptStatus.CREATED, Instant.now());
        var first = attemptService.claim(attempt.getId()).orElseThrow();
        backdateClaim(attempt.getId());
        var observed = executionRepository.findStaleInFlight(Duration.ofMinutes(1), 10).get(0);

        assertEquals(1, executionRepository.resetStuck(attempt.getId(), first.generation(), Duration.ofMinutes(1)));
        var replacement = attemptService.claim(attempt.getId()).orElseThrow();

        assertEquals(first.generation() + 1, replacement.generation());
        assertEquals(0, executionRepository.resetStuck(attempt.getId(), observed.generation(), Duration.ofMinutes(1)));
        assertEquals(AttemptStatus.IN_FLIGHT, attemptRepository.findById(attempt.getId()).orElseThrow().getStatus());
    }

    @Test
    void delayedObservedGeneration_afterReplacementTerminal_cannotResetOrEraseDiagnostics() throws Exception {
        Attempt attempt = persistAttemptWithUpdatedAt(AttemptStatus.CREATED, Instant.now());
        var first = attemptService.claim(attempt.getId()).orElseThrow();
        backdateClaim(attempt.getId());

        CountDownLatch candidateSelected = new CountDownLatch(1);
        CountDownLatch releaseSweeper = new CountDownLatch(1);
        AtomicInteger delayedResetRows = new AtomicInteger(-1);
        AtomicReference<List<com.example.relay.attempt.infrastructure.AttemptExecutionCandidate>> observedCandidates =
                new AtomicReference<>();
        AttemptExecutionRepository spyingRepository = new AttemptExecutionRepository() {
            @Override
            public Optional<AttemptExecutionClaim> claim(UUID attemptId) {
                return executionRepositoryDelegate.claim(attemptId);
            }

            @Override
            public List<com.example.relay.attempt.infrastructure.AttemptExecutionCandidate> findStaleInFlight(
                    Duration grace, int limit) {
                List<com.example.relay.attempt.infrastructure.AttemptExecutionCandidate> candidates =
                        executionRepositoryDelegate.findStaleInFlight(grace, limit);
                observedCandidates.set(candidates);
                candidateSelected.countDown();
                try {
                    if (!releaseSweeper.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("sweeper stayed gated after selecting its stale candidate");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("sweeper gate was interrupted", interrupted);
                }
                return candidates;
            }

            @Override
            public int resetStuck(UUID attemptId, long observedGeneration, Duration grace) {
                int rows = executionRepositoryDelegate.resetStuck(attemptId, observedGeneration, grace);
                delayedResetRows.set(rows);
                return rows;
            }

            @Override
            public int markSucceeded(AttemptExecution execution, Integer responseCode, String responseBody,
                    Long latencyMs) {
                return executionRepositoryDelegate.markSucceeded(execution, responseCode, responseBody, latencyMs);
            }

            @Override
            public int markFailed(AttemptExecution execution, AttemptStatus status, Instant nextRetryAt,
                    Integer responseCode, String responseBody, String lastError, Long latencyMs) {
                return executionRepositoryDelegate.markFailed(execution, status, nextRetryAt, responseCode,
                        responseBody, lastError, latencyMs);
            }
        };

        AttemptService delayedAttemptService = new AttemptService(attemptRepository, spyingRepository, deliveryRepository,
                allocationRepository, new com.example.relay.attempt.application.AttemptAllocationMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        ReconciliationSweeper delayedSweeper = new ReconciliationSweeper(attemptRepository, spyingRepository,
                attemptPublisher, delayedAttemptService, reconciliationProperties, executionOwnershipMetrics,
                ScheduledCallbackTestSupport.openRunner());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> sweep = executor.submit(delayedSweeper::sweep);
            assertEquals(true, candidateSelected.await(10, TimeUnit.SECONDS));
            assertEquals(1, observedCandidates.get().size());
            assertEquals(attempt.getId(), observedCandidates.get().get(0).id());
            assertEquals(first.generation(), observedCandidates.get().get(0).generation());
            assertNotNull(observedCandidates.get().get(0).claimedAt());
            assertEquals(1, executionRepository.resetStuck(attempt.getId(), first.generation(), Duration.ofMinutes(1)));
            var replacement = attemptService.claim(attempt.getId()).orElseThrow();
            attemptService.markSucceeded(replacement, 207, "replacement response", 321L);
            Attempt before = attemptRepository.findById(attempt.getId()).orElseThrow();
            releaseSweeper.countDown();
            sweep.get(10, TimeUnit.SECONDS);
            Attempt after = attemptRepository.findById(attempt.getId()).orElseThrow();

            assertEquals(AttemptStatus.SUCCEEDED, after.getStatus());
            assertEquals(before.getExecutionGeneration(), after.getExecutionGeneration());
            assertEquals(before.getResponseCode(), after.getResponseCode());
            assertEquals(before.getResponseBody(), after.getResponseBody());
            assertEquals(before.getLastError(), after.getLastError());
            assertEquals(before.getLatencyMs(), after.getLatencyMs());
            assertNull(after.getExecutionClaimedAt());
            assertEquals(0, delayedResetRows.get(), "the delayed sweeper's real reset SQL must lose the race");
        } finally {
            releaseSweeper.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void completionBeforeReset_causesResetToLose() {
        Attempt attempt = persistAttemptWithUpdatedAt(AttemptStatus.CREATED, Instant.now());
        var execution = attemptService.claim(attempt.getId()).orElseThrow();
        backdateClaim(attempt.getId());
        var candidate = executionRepository.findStaleInFlight(Duration.ofMinutes(1), 10).get(0);
        attemptService.markSucceeded(execution, 201, "finished first", 19L);

        assertEquals(0, executionRepository.resetStuck(attempt.getId(), candidate.generation(), Duration.ofMinutes(1)));
        assertEquals(AttemptStatus.SUCCEEDED, attemptRepository.findById(attempt.getId()).orElseThrow().getStatus());
    }

    @Test
    void resetBeforeCompletion_fencesOldOwnerImmediately() {
        Attempt attempt = persistAttemptWithUpdatedAt(AttemptStatus.CREATED, Instant.now());
        var execution = attemptService.claim(attempt.getId()).orElseThrow();
        backdateClaim(attempt.getId());

        assertEquals(1, executionRepository.resetStuck(attempt.getId(), execution.generation(), Duration.ofMinutes(1)));
        assertEquals(com.example.relay.attempt.application.AttemptMutationOutcome.OWNERSHIP_LOST,
                attemptService.markSucceeded(execution, 201, "too late", 19L));
        assertEquals(AttemptStatus.CREATED, attemptRepository.findById(attempt.getId()).orElseThrow().getStatus());
    }

    @Test
    void abandonedGeneration_resetsAndIsReclaimedAtHigherGeneration() {
        Attempt attempt = persistAttemptWithUpdatedAt(AttemptStatus.CREATED, Instant.now());
        var original = attemptService.claim(attempt.getId()).orElseThrow();
        backdateClaim(attempt.getId());

        sweeper.sweep();

        var reclaimed = attemptService.claim(attempt.getId()).orElseThrow();
        assertEquals(original.generation() + 1, reclaimed.generation());
    }

    @Test
    void updatedAtChange_doesNotReplaceExecutionClaimAgeAuthority() {
        Attempt attempt = persistAttemptWithUpdatedAt(AttemptStatus.CREATED, Instant.now());
        attemptService.claim(attempt.getId());
        backdateUpdatedAt(attempt.getId(), Instant.now().minusSeconds(3600));

        sweeper.sweep();

        assertEquals(AttemptStatus.IN_FLIGHT, attemptRepository.findById(attempt.getId()).orElseThrow().getStatus());
    }

    @Test
    void inFlightAttempt_thatFinishesConcurrently_isNotResetOrDuplicated() {
        // A terminal completion clears execution_claimed_at, so a later sweeper selection no
        // longer sees this row as stale IN_FLIGHT work. The delayed-selection race is exercised
        // separately with latches in delayedObservedGeneration_afterReplacementTerminal_cannotResetOrEraseDiagnostics.
        Attempt attempt = persistAttemptWithUpdatedAt(AttemptStatus.CREATED, Instant.now().minusSeconds(3600));
        var execution = attemptService.claim(attempt.getId()).orElseThrow();
        backdateUpdatedAt(attempt.getId(), Instant.now().minusSeconds(3600));
        attemptService.markSucceeded(execution, 204, "completed", 9L);

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

        attemptService.claim(attempt.getId()); // should stamp updated_at to ~now (Task 2's fix)

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
