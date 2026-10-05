package com.example.relay.delivery.application;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.application.AttemptExecution;
import com.example.relay.attempt.application.AttemptMutationOutcome;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptAllocationRepository;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.domain.DeliveryStatus;
import com.example.relay.delivery.exception.DeliveryNotDeadException;
import com.example.relay.delivery.exception.ReplayEndpointInactiveException;
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
import com.example.relay.user.infrastructure.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real service transactions, latches and observed PostgreSQL blockers prove replay ordering. */
@Tag("integration")
@SpringBootTest
class DeliveryReplayConcurrencyPostgresTest implements SharedPostgresContainer {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Instant RETRY_AT = Instant.parse("2030-01-01T00:00:00Z");

    @Autowired
    DeliveryReplayService deliveryReplayService;
    @MockitoSpyBean
    AttemptService attemptService;
    @Autowired
    AttemptRepository attemptRepository;
    @MockitoSpyBean
    AttemptAllocationRepository allocationRepository;
    @Autowired
    DeliveryRepository deliveryRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    EnvironmentRepository environmentRepository;
    @Autowired
    AppRepository appRepository;
    @Autowired
    EventRepository eventRepository;
    @Autowired
    EndpointRepository endpointRepository;
    @Autowired
    MessageRepository messageRepository;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactionManager;

    private ExecutorService executor;
    private TransactionTemplate transactions;
    private final List<CountDownLatch> releaseGates = new ArrayList<>();
    private final ThreadLocal<ReplayBoundary> replayBoundary = new ThreadLocal<>();
    private final ThreadLocal<ReplayBoundary> retryBoundary = new ThreadLocal<>();
    private User user;
    private Environment environment;
    private App app;
    private Event event;
    private Endpoint endpoint;
    private Delivery delivery;
    private InsertBarrier insertBarrier;
    private AttemptAllocationRepository allocationSpy;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(3);
        transactions = new TransactionTemplate(transactionManager);
        // Stub the target so MANDATORY advice does not run while configuring a barrier outside a transaction.
        allocationSpy = AopTestUtils.getUltimateTargetObject(allocationRepository);
        user = userRepository.save(new User("replay-" + UUID.randomUUID() + "@mail.com", "hash"));
        environment = environmentRepository.save(new Environment("Env", "Desc", user));
        app = appRepository.save(new App("App", environment));
        event = eventRepository.save(new Event("payment.completed", app));
        endpoint = endpointRepository.save(new Endpoint("EP", "https://example.com/hook", "secret", app));
        delivery = createDeadDelivery();
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                    "the service proxy must own the allocation transaction");
            ReplayBoundary boundary = replayBoundary.get();
            if (boundary != null) {
                boundary.pid().set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                boundary.reached().countDown();
                if (boundary.release() != null) {
                    awaitGate(boundary.release());
                }
            }
            Attempt created = (Attempt) invocation.callRealMethod();
            if (insertBarrier != null && insertBarrier.afterInsert()
                    && created.getDelivery().getId().equals(insertBarrier.deliveryId())) {
                insertBarrier.reached().countDown();
                awaitGate(insertBarrier.release());
            }
            return created;
        }).when(attemptService).createReplay(any());
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                    "the service proxy must own the fenced retry/allocation transaction");
            ReplayBoundary boundary = retryBoundary.get();
            if (boundary != null) {
                boundary.pid().set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                boundary.reached().countDown();
            }
            return invocation.callRealMethod();
        }).when(attemptService).markFailedAndCreateRetry(any(), any(), any(), any(), any(), any());
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        releaseGates.forEach(CountDownLatch::countDown);
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "transactions must finish");
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM attempts WHERE app_id = ?", app.getId());
            jdbc.update("DELETE FROM deliveries WHERE app_id = ?", app.getId());
            jdbc.update("DELETE FROM messages WHERE app_id = ?", app.getId());
            jdbc.update("DELETE FROM endpoints WHERE app_id = ?", app.getId());
            jdbc.update("DELETE FROM events WHERE app_id = ?", app.getId());
            jdbc.update("DELETE FROM apps WHERE id = ?", app.getId());
            jdbc.update("DELETE FROM environments WHERE id = ?", environment.getId());
            jdbc.update("DELETE FROM users WHERE id = ?", user.getId());
        });
    }

    @Test
    void twoConcurrentReplays_areSerializedAndOnlyCurrentEligibleRequestSucceeds() throws Exception {
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch commit = releaseGate();
        pauseSave(delivery.getId(), inserted, commit, true);
        Task<DeliveryStatus> first = startReplay(delivery.getId());
        awaitGate(inserted);
        Task<DeliveryStatus> second = startReplay(delivery.getId());
        assertBlockedBy(second, first, "FROM deliveries", "FOR UPDATE");
        assertEquals(List.of(1, 2, 3, 4, 5, 6), attemptNumbers(delivery.getId()));
        commit.countDown();
        assertEquals(7, result(first).getAttemptNo());
        Throwable rejection = failure(second);
        assertInstanceOf(DeliveryNotDeadException.class, rejection);
        assertTrue(rejection.getMessage().contains("CREATED"));
        assertCreatedReplay(7);
    }

    @Test
    void delayedReplayAfterCompetingReplaySucceeds_isRejectedWithoutDuplicateOrExecutableWork() throws Exception {
        CountDownLatch delayed = new CountDownLatch(1);
        CountDownLatch resume = releaseGate();
        Task<DeliveryStatus> waiter = startReplay(delivery.getId(), delayed, resume);
        awaitGate(delayed);
        DeliveryStatus winner = replay(delivery.getId());
        assertEquals(7, winner.getAttemptNo());
        AttemptExecution execution = attemptService.claim(winner.getLatestAttemptId()).orElseThrow();
        assertEquals(1, execution.generation());
        assertNotNull(execution.claimedAt());
        assertEquals(AttemptMutationOutcome.APPLIED, attemptService.markSucceeded(execution, 200, "ok", 10L));
        resume.countDown();
        Throwable rejection = failure(waiter);
        assertInstanceOf(DeliveryNotDeadException.class, rejection);
        assertTrue(rejection.getMessage().contains("SUCCEEDED"));
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), attemptNumbers(delivery.getId()));
        assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(*) FROM attempts WHERE delivery_id = ? AND status = 'CREATED'",
                        Integer.class, delivery.getId()));
        assertTerminalExecution(winner.getLatestAttemptId(), AttemptStatus.SUCCEEDED);
    }

    @Test
    void delayedReplayAfterCompetingReplayBecomesDead_getsNextNumber() throws Exception {
        CountDownLatch delayed = new CountDownLatch(1);
        CountDownLatch resume = releaseGate();
        Task<DeliveryStatus> waiter = startReplay(delivery.getId(), delayed, resume);
        awaitGate(delayed);
        DeliveryStatus winner = replay(delivery.getId());
        AttemptExecution execution = attemptService.claim(winner.getLatestAttemptId()).orElseThrow();
        assertEquals(1, execution.generation());
        assertEquals(AttemptMutationOutcome.APPLIED,
                attemptService.markFailed(execution, AttemptStatus.DEAD, null, 503, "failed", "unavailable", 10L));
        resume.countDown();
        DeliveryStatus laterReplay = result(waiter);
        assertEquals(8, laterReplay.getAttemptNo());
        assertEquals(8, laterReplay.getAttemptCount());
        assertTerminalExecution(winner.getLatestAttemptId(), AttemptStatus.DEAD);
        assertCreatedReplay(8);
    }

    @Test
    void replayFirstHoldsEndpointShareThroughCommitAndThenDeactivateProceeds() throws Exception {
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch commit = releaseGate();
        pauseSave(delivery.getId(), inserted, commit, true);
        Task<DeliveryStatus> replay = startReplay(delivery.getId());
        awaitGate(inserted);
        Task<Integer> deactivate = startTransaction(() -> deactivateEndpoint());
        assertBlockedBy(deactivate, replay, "UPDATE endpoints", "is_active");
        assertTrue(endpointActive());
        assertEquals(6, attemptNumbers(delivery.getId()).size(), "the flushed replay is still uncommitted");
        commit.countDown();
        assertEquals(7, result(replay).getAttemptNo());
        assertEquals(1, result(deactivate));
        assertFalse(endpointActive());
        assertCreatedReplay(7);
    }

    @Test
    void deactivateFirstMakesReplayObserveInactiveAndReject() throws Exception {
        CountDownLatch deactivated = new CountDownLatch(1);
        CountDownLatch commit = releaseGate();
        Task<Integer> deactivate = startTransaction(() -> {
            int rows = deactivateEndpoint();
            deactivated.countDown();
            awaitGate(commit);
            return rows;
        });
        awaitGate(deactivated);
        Task<DeliveryStatus> replay = startReplay(delivery.getId());
        assertBlockedBy(replay, deactivate, "FROM endpoints", "FOR SHARE");
        commit.countDown();
        assertEquals(1, result(deactivate));
        assertInstanceOf(ReplayEndpointInactiveException.class, failure(replay));
        assertEquals(List.of(1, 2, 3, 4, 5, 6), attemptNumbers(delivery.getId()));
    }

    @Test
    void replayAndEndpointDeleteDoNotDeadlock() throws Exception {
        CountDownLatch beforeInsert = new CountDownLatch(1);
        CountDownLatch insert = releaseGate();
        pauseSave(delivery.getId(), beforeInsert, insert, false);
        Task<DeliveryStatus> replay = startReplay(delivery.getId());
        awaitGate(beforeInsert);
        Task<Integer> delete =
                startTransaction(() -> jdbc.update("DELETE FROM endpoints WHERE id = ?", endpoint.getId()));
        assertBlockedBy(delete, replay, "DELETE FROM endpoints", "id");
        insert.countDown();
        assertEquals(7, result(replay).getAttemptNo());
        Throwable deleteFailure = failure(delete);
        assertFalse(hasSqlState(deleteFailure, "40P01"));
        assertTrue(hasSqlState(deleteFailure, "23503"),
                "the existing child FK contract forbids deleting this Endpoint");
        assertTrue(endpointActive());
        assertCreatedReplay(7);
    }

    @Test
    void distinctDeliveriesOnSameEndpointRemainConcurrentAtEndpointLock() throws Exception {
        Delivery other = createDeadDelivery();
        CountDownLatch bothAtInsert = new CountDownLatch(2);
        CountDownLatch insert = releaseGate();
        doAnswer(invocation -> {
            int number = (int) invocation.callRealMethod();
            bothAtInsert.countDown();
            awaitGate(insert);
            return number;
        }).when(allocationSpy).nextAttemptNoUnderDeliveryLock(any());
        Task<DeliveryStatus> first = startReplay(delivery.getId());
        Task<DeliveryStatus> second = startReplay(other.getId());
        awaitGate(bothAtInsert);
        assertNotEquals(first.pid().get(), second.pid().get());
        assertFalse(first.future().isDone());
        assertFalse(second.future().isDone());
        insert.countDown();
        assertEquals(7, result(first).getAttemptNo());
        assertEquals(7, result(second).getAttemptNo());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), attemptNumbers(other.getId()));
        assertCreatedReplay(7);
    }

    @Test
    void replayWaitingBehindRetryCreation_observesScheduledLatestAndCreatesNothing() throws Exception {
        AttemptExecution owner = prepareRetry();
        CountDownLatch allocated = new CountDownLatch(1);
        CountDownLatch insert = releaseGate();
        pauseSave(delivery.getId(), allocated, insert, false);
        Task<AttemptMutationOutcome> retry = startRetry(owner);
        awaitGate(allocated);
        Task<DeliveryStatus> replay = startReplay(delivery.getId());

        // Reaching Delivery proves replay acquired its compatible Endpoint SHARE lock first.
        assertBlockedBy(replay, retry, "FROM deliveries", "FOR UPDATE");
        assertEquals(List.of(1), attemptNumbers(delivery.getId()), "the child is not committed yet");
        assertEquals("IN_FLIGHT", persistedStatus(owner.attempt().getId()), "the parent is not committed yet");
        insert.countDown();

        assertEquals(AttemptMutationOutcome.APPLIED, result(retry));
        Throwable rejection = failure(replay);
        assertInstanceOf(DeliveryNotDeadException.class, rejection);
        assertTrue(rejection.getMessage().contains("SCHEDULED"));
        assertEquals("FAILED_RETRYING", persistedStatus(owner.attempt().getId()));
        assertScheduledRetry(2);
    }

    @Test
    void twoCompletionsForOneGeneration_allocateOnlyOneChildNumber() throws Exception {
        AttemptExecution owner = prepareRetry();
        CountDownLatch allocated = new CountDownLatch(1);
        CountDownLatch insert = releaseGate();
        pauseSave(delivery.getId(), allocated, insert, false);
        Task<AttemptMutationOutcome> first = startRetry(owner);
        awaitGate(allocated);
        Task<AttemptMutationOutcome> second = startRetry(owner);

        assertBlockedBy(second, first, "FROM deliveries", "FOR UPDATE");
        insert.countDown();
        assertEquals(AttemptMutationOutcome.APPLIED, result(first));
        assertEquals(AttemptMutationOutcome.OWNERSHIP_LOST, result(second));
        assertScheduledRetry(2);
        assertEquals("FAILED_RETRYING", persistedStatus(owner.attempt().getId()));
    }

    @Test
    void retryLocksEndpointKeyShareBeforeDeliveryUpdate() throws Exception {
        AttemptExecution owner = prepareRetry();
        CountDownLatch endpointLocked = new CountDownLatch(1);
        CountDownLatch unlockEndpoint = releaseGate();
        Task<UUID> endpointLocker = startTransaction(() -> {
            UUID id = jdbc.queryForObject("SELECT id FROM endpoints WHERE id = ? FOR UPDATE", UUID.class,
                    endpoint.getId());
            endpointLocked.countDown();
            awaitGate(unlockEndpoint);
            return id;
        });
        awaitGate(endpointLocked);
        Task<AttemptMutationOutcome> retry = startRetry(owner);
        assertBlockedBy(retry, endpointLocker, "FROM endpoints", "FOR KEY SHARE");

        // The Delivery must still be available while retry is waiting for its first parent lock.
        Task<UUID> deliveryLocker = startTransaction(() -> jdbc
                .queryForObject("SELECT id FROM deliveries WHERE id = ? FOR UPDATE", UUID.class, delivery.getId()));
        assertEquals(delivery.getId(), result(deliveryLocker));
        assertEquals("IN_FLIGHT", persistedStatus(owner.attempt().getId()));
        unlockEndpoint.countDown();
        assertEquals(endpoint.getId(), result(endpointLocker));
        assertEquals(AttemptMutationOutcome.APPLIED, result(retry));
        assertScheduledRetry(2);
    }

    @Test
    void ownershipLostWhileRetryWaitsForDelivery_createsNoChildAndConsumesNoNumber() throws Exception {
        AttemptExecution owner = prepareRetry();
        CountDownLatch deliveryLocked = new CountDownLatch(1);
        CountDownLatch unlockDelivery = releaseGate();
        Task<UUID> deliveryLocker = startTransaction(() -> {
            UUID id = jdbc.queryForObject("SELECT id FROM deliveries WHERE id = ? FOR UPDATE", UUID.class,
                    delivery.getId());
            deliveryLocked.countDown();
            awaitGate(unlockDelivery);
            return id;
        });
        awaitGate(deliveryLocked);
        Task<AttemptMutationOutcome> retry = startRetry(owner);
        assertBlockedBy(retry, deliveryLocker, "FROM deliveries", "FOR UPDATE");

        // A non-allocating P04 completion can win because retry has not written/locked the Attempt yet.
        Task<AttemptMutationOutcome> success =
                startTransaction(() -> attemptService.markSucceeded(owner, 204, "winner", 9L));
        assertEquals(AttemptMutationOutcome.APPLIED, result(success));
        unlockDelivery.countDown();
        assertEquals(delivery.getId(), result(deliveryLocker));
        assertEquals(AttemptMutationOutcome.OWNERSHIP_LOST, result(retry));
        assertEquals(List.of(1), attemptNumbers(delivery.getId()));
        assertEquals("SUCCEEDED", persistedStatus(owner.attempt().getId()));
        assertEquals("winner", jdbc.queryForObject("SELECT response_body FROM attempts WHERE id = ?", String.class,
                owner.attempt().getId()));
    }

    @Test
    void retryAndEndpointDeleteDoNotDeadlock() throws Exception {
        AttemptExecution owner = prepareRetry();
        CountDownLatch allocated = new CountDownLatch(1);
        CountDownLatch insert = releaseGate();
        pauseSave(delivery.getId(), allocated, insert, false);
        Task<AttemptMutationOutcome> retry = startRetry(owner);
        awaitGate(allocated);
        Task<Integer> delete =
                startTransaction(() -> jdbc.update("DELETE FROM endpoints WHERE id = ?", endpoint.getId()));
        assertBlockedBy(delete, retry, "DELETE FROM endpoints", "id");
        insert.countDown();

        assertEquals(AttemptMutationOutcome.APPLIED, result(retry));
        Throwable deleteFailure = failure(delete);
        assertFalse(hasSqlState(deleteFailure, "40P01"));
        assertTrue(hasSqlState(deleteFailure, "23503"), "the existing parent FK prevents Endpoint deletion");
        assertScheduledRetry(2);
    }

    @Test
    void retryEndpointKeyShareRemainsCompatibleWithDeactivate() throws Exception {
        AttemptExecution owner = prepareRetry();
        CountDownLatch allocated = new CountDownLatch(1);
        CountDownLatch insert = releaseGate();
        pauseSave(delivery.getId(), allocated, insert, false);
        Task<AttemptMutationOutcome> retry = startRetry(owner);
        awaitGate(allocated);

        Task<Integer> deactivate = startTransaction(this::deactivateEndpoint);
        assertEquals(1, result(deactivate), "a non-key update commits while retry retains KEY SHARE");
        assertFalse(endpointActive());
        assertFalse(retry.future().isDone(), "retry still holds its allocation transaction at the insert barrier");
        insert.countDown();
        assertEquals(AttemptMutationOutcome.APPLIED, result(retry));
        assertScheduledRetry(2);
    }

    private AttemptExecution prepareRetry() {
        Message message = messageRepository.save(new Message(app, event, new ObjectMapper().createObjectNode()));
        delivery = deliveryRepository.save(new Delivery(app, message, endpoint));
        Attempt first = attemptRepository.save(new Attempt(app, message, endpoint, delivery, 1));
        return attemptService.claim(first.getId()).orElseThrow();
    }

    private String persistedStatus(UUID attemptId) {
        return jdbc.queryForObject("SELECT status FROM attempts WHERE id = ?", String.class, attemptId);
    }

    private void assertScheduledRetry(int number) {
        assertEquals(List.of(1, 2), attemptNumbers(delivery.getId()));
        var child = jdbc.queryForMap("SELECT status, execution_generation, execution_claimed_at "
                + "FROM attempts WHERE delivery_id = ? AND attempt_no = ?", delivery.getId(), number);
        assertEquals("SCHEDULED", child.get("status"));
        assertEquals(0L, ((Number) child.get("execution_generation")).longValue());
        assertNull(child.get("execution_claimed_at"));
        assertEquals(RETRY_AT,
                jdbc.queryForObject("SELECT next_retry_at FROM attempts WHERE delivery_id = ? AND attempt_no = ?",
                        Instant.class, delivery.getId(), number));
    }

    private Delivery createDeadDelivery() {
        Message message = messageRepository.save(new Message(app, event, new ObjectMapper().createObjectNode()));
        Delivery result = deliveryRepository.save(new Delivery(app, message, endpoint));
        for (int number = 1; number <= 6; number++) {
            Attempt attempt = new Attempt(app, message, endpoint, result, number);
            attempt.setStatus(AttemptStatus.DEAD);
            attemptRepository.save(attempt);
        }
        return result;
    }

    private void pauseSave(UUID deliveryId, CountDownLatch reached, CountDownLatch release, boolean afterInsert) {
        insertBarrier = new InsertBarrier(deliveryId, reached, release, afterInsert);
        if (afterInsert) {
            return;
        }
        doAnswer(invocation -> {
            int number = (int) invocation.callRealMethod();
            if (invocation.getArgument(0).equals(deliveryId)) {
                reached.countDown();
                awaitGate(release);
            }
            return number;
        }).when(allocationSpy).nextAttemptNoUnderDeliveryLock(any());
    }

    private DeliveryStatus replay(UUID deliveryId) {
        return deliveryReplayService.replay(deliveryId, app.getId(), environment.getId(), user.getId());
    }

    private Task<DeliveryStatus> startReplay(UUID deliveryId) {
        return startReplay(deliveryId, new CountDownLatch(1), null);
    }

    private Task<DeliveryStatus> startReplay(UUID deliveryId, CountDownLatch reached, CountDownLatch release) {
        AtomicInteger pid = new AtomicInteger();
        Future<DeliveryStatus> future = executor.submit(() -> {
            replayBoundary.set(new ReplayBoundary(pid, reached, release));
            try {
                return replay(deliveryId);
            } finally {
                replayBoundary.remove();
            }
        });
        return new Task<>(future, pid);
    }

    private Task<AttemptMutationOutcome> startRetry(AttemptExecution execution) {
        AtomicInteger pid = new AtomicInteger();
        Future<AttemptMutationOutcome> future = executor.submit(() -> {
            retryBoundary.set(new ReplayBoundary(pid, new CountDownLatch(1), null));
            try {
                return attemptService.markFailedAndCreateRetry(execution, RETRY_AT, 503, "retry response", "error",
                        12L);
            } finally {
                retryBoundary.remove();
            }
        });
        return new Task<>(future, pid);
    }

    private <T> Task<T> startTransaction(Supplier<T> work) {
        AtomicInteger pid = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        Future<T> future = executor.submit(() -> transactions.execute(status -> {
            pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
            started.countDown();
            return work.get();
        }));
        awaitGate(started);
        return new Task<>(future, pid);
    }

    private void assertBlockedBy(Task<?> waiter, Task<?> blocker, String queryPart, String lockPart) {
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(10)).untilAsserted(() -> {
            assertNotEquals(0, waiter.pid().get());
            assertNotEquals(waiter.pid().get(), blocker.pid().get());
            var activity = jdbc.queryForMap("""
                    SELECT query, wait_event_type, ? = ANY(pg_blocking_pids(pid)) AS blocked_by_expected
                    FROM pg_stat_activity WHERE pid = ?
                    """, blocker.pid().get(), waiter.pid().get());
            assertEquals(true, activity.get("blocked_by_expected"), activity.toString());
            assertEquals("Lock", activity.get("wait_event_type"));
            assertTrue(activity.get("query").toString().contains(queryPart), activity.toString());
            assertTrue(activity.get("query").toString().contains(lockPart), activity.toString());
        });
    }

    private List<Integer> attemptNumbers(UUID deliveryId) {
        return jdbc.queryForList("SELECT attempt_no FROM attempts WHERE delivery_id = ? ORDER BY attempt_no",
                Integer.class, deliveryId);
    }

    private void assertCreatedReplay(int number) {
        List<Integer> expected = number == 7 ? List.of(1, 2, 3, 4, 5, 6, 7) : List.of(1, 2, 3, 4, 5, 6, 7, 8);
        assertEquals(expected, attemptNumbers(delivery.getId()));
        Attempt latest =
                attemptRepository.findByDeliveryId(delivery.getId(), org.springframework.data.domain.Pageable.unpaged())
                        .stream().filter(attempt -> attempt.getAttemptNo() == number).findFirst().orElseThrow();
        assertEquals(AttemptStatus.CREATED, latest.getStatus());
        assertEquals(0, latest.getExecutionGeneration());
        assertNull(latest.getExecutionClaimedAt());
        assertNull(latest.getNextRetryAt());
    }

    private void assertTerminalExecution(UUID attemptId, AttemptStatus status) {
        Attempt attempt = attemptRepository.findById(attemptId).orElseThrow();
        assertEquals(status, attempt.getStatus());
        assertEquals(1, attempt.getExecutionGeneration());
        assertNull(attempt.getExecutionClaimedAt());
    }

    private int deactivateEndpoint() {
        return jdbc.update("UPDATE endpoints SET is_active = false WHERE id = ?", endpoint.getId());
    }

    private boolean endpointActive() {
        return jdbc.queryForObject("SELECT is_active FROM endpoints WHERE id = ?", Boolean.class, endpoint.getId());
    }

    private CountDownLatch releaseGate() {
        CountDownLatch gate = new CountDownLatch(1);
        releaseGates.add(gate);
        return gate;
    }

    private static void awaitGate(CountDownLatch gate) {
        try {
            assertTrue(gate.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "transaction barrier must be reached");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for transaction barrier", exception);
        }
    }

    private static <T> T result(Task<T> task) throws Exception {
        return task.future().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    private static Throwable failure(Task<?> task) {
        return assertThrows(ExecutionException.class, () -> result(task)).getCause();
    }

    private static boolean hasSqlState(Throwable failure, String state) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && state.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private record ReplayBoundary(AtomicInteger pid, CountDownLatch reached, CountDownLatch release) {
    }
    private record InsertBarrier(UUID deliveryId, CountDownLatch reached, CountDownLatch release, boolean afterInsert) {
    }
    private record Task<T>(Future<T> future, AtomicInteger pid) {
    }
}
