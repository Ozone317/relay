package com.example.relay.attempt.infrastructure;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import com.example.relay.app.domain.App;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.message.domain.Message;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.time.Duration;
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
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Real independent connections prove the allocation protocol, including its parent lock order. */
@Tag("integration")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AttemptAllocationRepositoryImpl.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttemptAllocationRepositoryPostgresTest implements SharedPostgresContainer {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    AttemptAllocationRepository repository;
    @Autowired
    TestEntityManager em;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactionManager;

    private TransactionTemplate firstTransactions;
    private TransactionTemplate secondTransactions;
    private ExecutorService executor;
    private final List<CountDownLatch> releaseGates = new ArrayList<>();
    private Fixture fixture;

    @BeforeEach
    void setUp() {
        firstTransactions = new TransactionTemplate(transactionManager);
        secondTransactions = new TransactionTemplate(transactionManager);
        executor = Executors.newFixedThreadPool(2);
        fixture = firstTransactions.execute(status -> {
            User user = em.persist(new User("allocation-" + UUID.randomUUID() + "@mail.com", "hash"));
            Environment environment = em.persist(new Environment("Env", "Desc", user));
            App app = em.persist(new App("App", environment));
            Event event = em.persist(new Event("allocation.event", app));
            Endpoint endpoint = em.persist(new Endpoint("EP", "https://example.com/hook", "secret", app));
            Message message = em.persist(new Message(app, event, new ObjectMapper().createObjectNode()));
            Delivery delivery = em.persist(new Delivery(app, message, endpoint));
            em.flush();
            return new Fixture(user.getId(), environment.getId(), app.getId(), event.getId(), endpoint.getId(),
                    message.getId(), delivery.getId());
        });
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        releaseGates.forEach(CountDownLatch::countDown);
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "transactions must finish");
        if (fixture != null) {
            firstTransactions.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM attempts WHERE app_id = ?", fixture.appId());
                jdbc.update("DELETE FROM deliveries WHERE app_id = ?", fixture.appId());
                jdbc.update("DELETE FROM messages WHERE app_id = ?", fixture.appId());
                jdbc.update("DELETE FROM endpoints WHERE app_id = ?", fixture.appId());
                jdbc.update("DELETE FROM events WHERE app_id = ?", fixture.appId());
                jdbc.update("DELETE FROM apps WHERE id = ?", fixture.appId());
                jdbc.update("DELETE FROM environments WHERE id = ?", fixture.environmentId());
                jdbc.update("DELETE FROM users WHERE id = ?", fixture.userId());
            });
        }
    }

    @Test
    void nextAttemptNoUnderDeliveryLockReturnsOneForEmptyDeliveryAndMaxPlusOneForHistory() {
        firstTransactions.executeWithoutResult(status -> {
            assertTrue(
                    repository.lockReplayAllocationParentsIfEndpointActive(fixture.endpointId(), fixture.deliveryId()));
            assertEquals(1, repository.nextAttemptNoUnderDeliveryLock(fixture.deliveryId()));
            insertAttempt(1);
            insertAttempt(2);
            insertAttempt(3);
            assertEquals(4, repository.nextAttemptNoUnderDeliveryLock(fixture.deliveryId()));
        });
    }

    @Test
    void allocationOperationsOutsideTransactionFailLoudly() {
        assertThrows(IllegalTransactionStateException.class, () -> repository
                .lockReplayAllocationParentsIfEndpointActive(fixture.endpointId(), fixture.deliveryId()));
        assertThrows(IllegalTransactionStateException.class,
                () -> repository.lockRetryAllocationParents(fixture.endpointId(), fixture.deliveryId()));
        assertThrows(IllegalTransactionStateException.class,
                () -> repository.nextAttemptNoUnderDeliveryLock(fixture.deliveryId()));
    }

    @Test
    void missingEndpointOrDeliveryFailsSafely() {
        UUID missing = UUID.randomUUID();
        UUID otherEndpoint = firstTransactions.execute(status -> {
            Endpoint endpoint = em.persist(
                    new Endpoint("Other", "https://example.com/other", "secret", em.find(App.class, fixture.appId())));
            em.flush();
            return endpoint.getId();
        });
        for (boolean replay : List.of(true, false)) {
            assertThrows(EmptyResultDataAccessException.class, () -> firstTransactions
                    .executeWithoutResult(status -> lockParents(replay, missing, fixture.deliveryId())));
            assertThrows(EmptyResultDataAccessException.class, () -> firstTransactions
                    .executeWithoutResult(status -> lockParents(replay, fixture.endpointId(), missing)));
            assertThrows(EmptyResultDataAccessException.class, () -> firstTransactions
                    .executeWithoutResult(status -> lockParents(replay, otherEndpoint, fixture.deliveryId())));
        }
    }

    @Test
    void replayLocksEndpointShareBeforeDeliveryUpdate() throws Exception {
        assertEndpointBeforeDelivery(true, "FOR SHARE");
        assertDeliveryUpdateLock(true);
    }

    @Test
    void retryLocksEndpointKeyShareBeforeDeliveryUpdate() throws Exception {
        assertEndpointBeforeDelivery(false, "FOR KEY SHARE");
        assertDeliveryUpdateLock(false);
    }

    @Test
    void deliveryLockSerializesTwoTransactionsAcrossConnections() throws Exception {
        firstTransactions.executeWithoutResult(status -> insertAttempt(1));
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch commitFirst = releaseGate();
        Task<Integer> first = start(firstTransactions, () -> {
            lockParents(true, fixture.endpointId(), fixture.deliveryId());
            int number = repository.nextAttemptNoUnderDeliveryLock(fixture.deliveryId());
            insertAttempt(number);
            firstLocked.countDown();
            awaitGate(commitFirst);
            return number;
        });
        awaitGate(firstLocked);
        Task<Integer> second = start(secondTransactions, () -> {
            lockParents(false, fixture.endpointId(), fixture.deliveryId());
            int number = repository.nextAttemptNoUnderDeliveryLock(fixture.deliveryId());
            insertAttempt(number);
            return number;
        });
        assertBlockedBy(second, first, "FROM deliveries", "FOR UPDATE");
        commitFirst.countDown();
        assertEquals(2, result(first));
        assertEquals(3, result(second));
        assertEquals(List.of(1, 2, 3), attemptNumbers());
    }

    @Test
    void rolledBackAllocationDoesNotConsumeNumber() throws Exception {
        firstTransactions.executeWithoutResult(status -> insertAttempt(1));
        RuntimeException sentinel = new RuntimeException("rollback after reading the next ordinal");
        CountDownLatch numberRead = new CountDownLatch(1);
        CountDownLatch rollback = releaseGate();
        Task<Void> first = start(firstTransactions, () -> {
            lockParents(true, fixture.endpointId(), fixture.deliveryId());
            assertEquals(2, repository.nextAttemptNoUnderDeliveryLock(fixture.deliveryId()));
            numberRead.countDown();
            awaitGate(rollback);
            throw sentinel;
        });
        awaitGate(numberRead);
        Task<Integer> second = start(secondTransactions, () -> {
            // This incompatible Endpoint lock must wait until the first transaction rolls back.
            jdbc.queryForObject("SELECT id FROM endpoints WHERE id = ? FOR UPDATE", UUID.class, fixture.endpointId());
            // Once Endpoint is released, NOWAIT independently proves Delivery was also released.
            jdbc.queryForObject("SELECT id FROM deliveries WHERE id = ? FOR UPDATE NOWAIT", UUID.class,
                    fixture.deliveryId());
            lockParents(false, fixture.endpointId(), fixture.deliveryId());
            int number = repository.nextAttemptNoUnderDeliveryLock(fixture.deliveryId());
            insertAttempt(number);
            return number;
        });
        assertBlockedBy(second, first, "FROM endpoints", "FOR UPDATE");
        rollback.countDown();
        assertSame(sentinel, assertThrows(ExecutionException.class, () -> result(first)).getCause());
        assertEquals(2, result(second));
        assertEquals(List.of(1, 2), attemptNumbers());
    }

    @Test
    void replayFirstBlocksDeactivateUntilReplayCommit() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commitReplay = releaseGate();
        Task<Void> replay = start(firstTransactions, () -> {
            lockParents(true, fixture.endpointId(), fixture.deliveryId());
            locked.countDown();
            awaitGate(commitReplay);
            insertAttempt(repository.nextAttemptNoUnderDeliveryLock(fixture.deliveryId()));
            return null;
        });
        awaitGate(locked);
        Task<Integer> deactivate = start(secondTransactions, () -> deactivateEndpoint());
        assertBlockedBy(deactivate, replay, "UPDATE endpoints", "is_active");
        assertTrue(endpointActive());
        commitReplay.countDown();
        result(replay);
        assertEquals(1, result(deactivate));
        assertFalse(endpointActive());
        assertEquals(List.of(1), attemptNumbers());
    }

    @Test
    void deactivateFirstMakesReplayObserveInactiveAndSkipDeliveryLock() throws Exception {
        assertDeactivateFirst(false);
    }

    @Test
    void rolledBackDeactivateMakesReplayObserveActive() throws Exception {
        assertDeactivateFirst(true);
    }

    @Test
    void replayAndEndpointDeleteDoNotDeadlock() throws Exception {
        assertDeleteRace(true);
    }

    @Test
    void retryAndEndpointDeleteDoNotDeadlock() throws Exception {
        assertDeleteRace(false);
    }

    @Test
    void retryEndpointKeyShareDoesNotBlockDeactivate() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commitRetry = releaseGate();
        Task<Void> retry = start(firstTransactions, () -> {
            lockParents(false, fixture.endpointId(), fixture.deliveryId());
            locked.countDown();
            awaitGate(commitRetry);
            return null;
        });
        awaitGate(locked);
        Task<Integer> deactivate = start(secondTransactions, () -> deactivateEndpoint());
        assertEquals(1, result(deactivate));
        assertFalse(retry.future().isDone(), "retry still retains its parent locks");
        assertFalse(endpointActive());
        commitRetry.countDown();
        result(retry);
        // Inactive is a replay predicate only: retry must still lock this Endpoint and Delivery successfully.
        firstTransactions.executeWithoutResult(status -> {
            repository.lockRetryAllocationParents(fixture.endpointId(), fixture.deliveryId());
            assertEquals(1, repository.nextAttemptNoUnderDeliveryLock(fixture.deliveryId()));
            assertFalse(
                    repository.lockReplayAllocationParentsIfEndpointActive(fixture.endpointId(), UUID.randomUUID()));
        });
    }

    @Test
    void replayLocksForDistinctDeliveriesOnOneEndpointRemainConcurrent() throws Exception {
        UUID secondDelivery = firstTransactions.execute(status -> {
            App app = em.find(App.class, fixture.appId());
            Message message = em.persist(
                    new Message(app, em.find(Event.class, fixture.eventId()), new ObjectMapper().createObjectNode()));
            Delivery delivery = em.persist(new Delivery(app, message, em.find(Endpoint.class, fixture.endpointId())));
            em.flush();
            return delivery.getId();
        });
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch secondLocked = new CountDownLatch(1);
        CountDownLatch commit = releaseGate();
        Task<Void> first = start(firstTransactions, () -> {
            lockParents(true, fixture.endpointId(), fixture.deliveryId());
            firstLocked.countDown();
            awaitGate(commit);
            return null;
        });
        awaitGate(firstLocked);
        Task<Void> second = start(secondTransactions, () -> {
            lockParents(true, fixture.endpointId(), secondDelivery);
            secondLocked.countDown();
            awaitGate(commit);
            return null;
        });
        awaitGate(secondLocked);
        assertNotEquals(first.pid().get(), second.pid().get());
        assertFalse(first.future().isDone());
        assertFalse(second.future().isDone());
        commit.countDown();
        result(first);
        result(second);
    }

    private void assertEndpointBeforeDelivery(boolean replay, String endpointLock) throws Exception {
        CountDownLatch endpointLocked = new CountDownLatch(1);
        CountDownLatch probeDelivery = releaseGate();
        CountDownLatch deliveryProbed = new CountDownLatch(1);
        CountDownLatch releaseParents = releaseGate();
        Task<Void> blocker = start(firstTransactions, () -> {
            jdbc.queryForObject("SELECT id FROM endpoints WHERE id = ? FOR UPDATE", UUID.class, fixture.endpointId());
            endpointLocked.countDown();
            awaitGate(probeDelivery);
            // While allocation waits on Endpoint, Delivery must still be available to this other connection.
            jdbc.queryForObject("SELECT id FROM deliveries WHERE id = ? FOR UPDATE NOWAIT", UUID.class,
                    fixture.deliveryId());
            deliveryProbed.countDown();
            awaitGate(releaseParents);
            return null;
        });
        awaitGate(endpointLocked);
        Task<Void> allocator = start(secondTransactions, () -> {
            lockParents(replay, fixture.endpointId(), fixture.deliveryId());
            return null;
        });
        assertBlockedBy(allocator, blocker, "FROM endpoints", endpointLock);
        probeDelivery.countDown();
        awaitGate(deliveryProbed);
        releaseParents.countDown();
        result(blocker);
        result(allocator);
    }

    private void assertDeliveryUpdateLock(boolean replay) throws Exception {
        CountDownLatch deliveryLocked = new CountDownLatch(1);
        CountDownLatch commit = releaseGate();
        Task<Void> blocker = start(firstTransactions, () -> {
            jdbc.queryForObject("SELECT id FROM deliveries WHERE id = ? FOR KEY SHARE", UUID.class,
                    fixture.deliveryId());
            deliveryLocked.countDown();
            awaitGate(commit);
            return null;
        });
        awaitGate(deliveryLocked);
        Task<Void> allocator = start(secondTransactions, () -> {
            lockParents(replay, fixture.endpointId(), fixture.deliveryId());
            return null;
        });
        // KEY SHARE would permit NO KEY UPDATE: this verifies the stronger required Delivery FOR UPDATE.
        assertBlockedBy(allocator, blocker, "FROM deliveries", "FOR UPDATE");
        commit.countDown();
        result(blocker);
        result(allocator);
    }

    private void assertDeactivateFirst(boolean rollback) throws Exception {
        RuntimeException sentinel = new RuntimeException("rollback deactivation");
        CountDownLatch deactivated = new CountDownLatch(1);
        CountDownLatch finishDeactivate = releaseGate();
        CountDownLatch replayDecided = new CountDownLatch(1);
        CountDownLatch commitReplay = releaseGate();
        Task<Void> deactivate = start(firstTransactions, () -> {
            assertEquals(1, deactivateEndpoint());
            deactivated.countDown();
            awaitGate(finishDeactivate);
            if (rollback) {
                throw sentinel;
            }
            return null;
        });
        awaitGate(deactivated);
        Task<Boolean> replay = start(secondTransactions, () -> {
            boolean active =
                    repository.lockReplayAllocationParentsIfEndpointActive(fixture.endpointId(), fixture.deliveryId());
            replayDecided.countDown();
            awaitGate(commitReplay);
            return active;
        });
        assertBlockedBy(replay, deactivate, "FROM endpoints", "FOR SHARE");
        finishDeactivate.countDown();
        if (rollback) {
            assertSame(sentinel, assertThrows(ExecutionException.class, () -> result(deactivate)).getCause());
        } else {
            result(deactivate);
        }
        awaitGate(replayDecided);
        if (!rollback) {
            firstTransactions.executeWithoutResult(status -> jdbc.queryForObject(
                    "SELECT id FROM deliveries WHERE id = ? FOR UPDATE NOWAIT", UUID.class, fixture.deliveryId()));
        }
        commitReplay.countDown();
        assertEquals(rollback, result(replay));
        assertEquals(rollback, endpointActive());
        assertEquals(List.of(), attemptNumbers());
    }

    private void assertDeleteRace(boolean replay) throws Exception {
        CountDownLatch parentsLocked = new CountDownLatch(1);
        CountDownLatch finishAllocation = releaseGate();
        Task<Integer> allocator = start(firstTransactions, () -> {
            lockParents(replay, fixture.endpointId(), fixture.deliveryId());
            parentsLocked.countDown();
            awaitGate(finishAllocation);
            // The original retry deadlock happened at this INSERT's implicit Endpoint FK KEY SHARE.
            int number = repository.nextAttemptNoUnderDeliveryLock(fixture.deliveryId());
            insertAttempt(number);
            return number;
        });
        awaitGate(parentsLocked);
        Task<Integer> delete = start(secondTransactions,
                () -> jdbc.update("DELETE FROM endpoints WHERE id = ?", fixture.endpointId()));
        assertBlockedBy(delete, allocator, "DELETE FROM endpoints", "id");
        finishAllocation.countDown();
        assertEquals(1, result(allocator), "ordered parents must permit allocation to commit while delete waits");
        ExecutionException deleteFailure = assertThrows(ExecutionException.class, () -> result(delete));
        assertFalse(hasSqlState(deleteFailure, "40P01"), "Endpoint delete must not be a deadlock victim");
        assertTrue(hasSqlState(deleteFailure, "23503"), "existing child FKs still forbid Endpoint deletion");
        assertTrue(endpointActive());
        assertEquals(List.of(1), attemptNumbers());
    }

    private void lockParents(boolean replay, UUID endpointId, UUID deliveryId) {
        if (replay) {
            assertTrue(repository.lockReplayAllocationParentsIfEndpointActive(endpointId, deliveryId));
        } else {
            repository.lockRetryAllocationParents(endpointId, deliveryId);
        }
    }

    private int deactivateEndpoint() {
        return jdbc.update("UPDATE endpoints SET is_active = false WHERE id = ?", fixture.endpointId());
    }

    private boolean endpointActive() {
        return jdbc.queryForObject("SELECT is_active FROM endpoints WHERE id = ?", Boolean.class, fixture.endpointId());
    }

    private void insertAttempt(int number) {
        jdbc.update("""
                INSERT INTO attempts (id, app_id, message_id, endpoint_id, delivery_id, attempt_no, status,
                                      created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'DEAD', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), fixture.appId(), fixture.messageId(), fixture.endpointId(),
                fixture.deliveryId(), number);
    }

    private List<Integer> attemptNumbers() {
        return jdbc.queryForList("SELECT attempt_no FROM attempts WHERE delivery_id = ? ORDER BY attempt_no",
                Integer.class, fixture.deliveryId());
    }

    private <T> Task<T> start(TransactionTemplate transactions, Supplier<T> work) {
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
        assertNotEquals(waiter.pid().get(), blocker.pid().get(),
                "transactions must use separate PostgreSQL connections");
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(10)).untilAsserted(() -> {
            var activity = jdbc.queryForMap("""
                    SELECT query, wait_event_type, ? = ANY(pg_blocking_pids(pid)) AS blocked_by_expected
                    FROM pg_stat_activity WHERE pid = ?
                    """, blocker.pid().get(), waiter.pid().get());
            assertEquals(true, activity.get("blocked_by_expected"));
            assertEquals("Lock", activity.get("wait_event_type"));
            assertTrue(activity.get("query").toString().contains(queryPart), activity.toString());
            assertTrue(activity.get("query").toString().contains(lockPart), activity.toString());
        });
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

    private static boolean hasSqlState(Throwable failure, String state) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && state.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private record Task<T>(Future<T> future, AtomicInteger pid) {
    }

    private record Fixture(UUID userId, UUID environmentId, UUID appId, UUID eventId, UUID endpointId, UUID messageId,
            UUID deliveryId) {
    }
}
