package com.example.relay.attempt.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.relay.app.domain.App;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.message.domain.Message;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.transaction.TestTransaction;

@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReadyWorkRepositoryImpl.class, AttemptExecutionRepositoryImpl.class})
class ReadyWorkRepositoryPostgresTest implements SharedPostgresContainer {

    @Autowired
    private ReadyWorkRepository readyWorkRepository;

    @Autowired
    private AttemptRepository attemptRepository;

    @Autowired
    private AttemptExecutionRepository attemptExecutionRepository;

    @Autowired
    private TestEntityManager testEntityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private App app;
    private Message message;
    private int endpointSequence;

    @BeforeEach
    void setUp() throws Exception {
        // claimSkipsRowsLockedByIndependentTransaction commits its fixture so the worker
        // transactions can observe it. Clear those rows before every test to keep the other
        // batch-claim assertions independent of method execution order.
        attemptRepository.deleteAll();

        User user = new User("ready-work-" + UUID.randomUUID() + "@mail.com", "hash");
        Environment environment = new Environment("Environment", "Description", user);
        app = new App("App", environment);
        Event event = new Event("ready-work." + UUID.randomUUID(), app);
        message = new Message(app, event, new ObjectMapper().readTree("{\"name\":\"hello\"}"));

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(message);
    }

    @Test
    void promotesOnlyDueScheduledRowsAndClearsReadyWorkFields() {
        Attempt due = scheduledAttempt(Instant.now().minusSeconds(1));
        scheduledAttempt(Instant.now().plusSeconds(60));
        setReadyFields(due, Instant.now(), UUID.randomUUID(), Instant.now());
        testEntityManager.flush();

        assertThat(readyWorkRepository.promoteDueScheduled(10)).containsExactly(due.getId());

        Attempt promoted = reload(due);
        assertThat(promoted.getStatus()).isEqualTo(AttemptStatus.CREATED);
        assertThat(promoted.getReadyPublishedAt()).isNull();
        assertThat(promoted.getReadyDispatchClaimId()).isNull();
        assertThat(promoted.getReadyDispatchClaimedAt()).isNull();
    }

    @Test
    void legacyTaskCannotClaimScheduledAttempt() {
        Attempt attempt = scheduledAttempt(Instant.now().plusSeconds(60));

        assertThat(attemptRepository.claim(attempt.getId(), Instant.now())).isZero();
        assertThat(reload(attempt).getStatus()).isEqualTo(AttemptStatus.SCHEDULED);
    }

    @Test
    void freshCreatedAttemptIsImmediatelyLeaseableDespiteGrace() {
        Attempt attempt = createdAttempt();
        UUID claimId = UUID.randomUUID();

        assertThat(readyWorkRepository.claimUnpublishedReady(claimId, Duration.ofHours(1), 1))
                .containsExactly(attempt.getId());
        assertThat(reload(attempt).getReadyDispatchClaimId()).isEqualTo(claimId);
        assertThat(reload(attempt).getReadyDispatchClaimedAt()).isNotNull();
    }

    @Test
    void claimUsesOneBatchTokenForAllRows() {
        List<Attempt> attempts = createdAttempts(3);
        UUID claimId = UUID.randomUUID();

        assertThat(readyWorkRepository.claimUnpublishedReady(claimId, Duration.ofSeconds(5), 3))
                .containsExactlyInAnyOrderElementsOf(attempts.stream().map(Attempt::getId).toList());

        for (Attempt attempt : attempts) {
            assertThat(reload(attempt).getReadyDispatchClaimId()).isEqualTo(claimId);
        }
    }

    @Test
    void leaseBlocksReclaimUntilOlderThanGrace() {
        Attempt attempt = createdAttempt();
        UUID firstClaim = UUID.randomUUID();
        UUID secondClaim = UUID.randomUUID();
        readyWorkRepository.claimUnpublishedReady(firstClaim, Duration.ofSeconds(5), 1);

        assertThat(readyWorkRepository.claimUnpublishedReady(secondClaim, Duration.ofHours(1), 1)).isEmpty();

        jdbcTemplate.update(
                "UPDATE attempts SET ready_dispatch_claimed_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = ?",
                attempt.getId());

        assertThat(readyWorkRepository.claimUnpublishedReady(secondClaim, Duration.ofSeconds(5), 1))
                .containsExactly(attempt.getId());
        assertThat(reload(attempt).getReadyDispatchClaimId()).isEqualTo(secondClaim);
    }

    @Test
    void confirmedPublicationMarksReadyAndReleasesLease() {
        Attempt attempt = createdAttempt();
        UUID claimId = UUID.randomUUID();
        readyWorkRepository.claimUnpublishedReady(claimId, Duration.ofSeconds(5), 1);

        assertThat(readyWorkRepository.markReadyPublished(attempt.getId(), claimId)).isEqualTo(1);

        Attempt published = reload(attempt);
        assertThat(published.getReadyPublishedAt()).isNotNull();
        assertThat(published.getReadyDispatchClaimId()).isNull();
        assertThat(published.getReadyDispatchClaimedAt()).isNull();
    }

    @Test
    void inFlightToCreatedClearsAllReadyWorkFields() {
        Attempt attempt = createdAttempt();
        setReadyFields(attempt, Instant.now(), UUID.randomUUID(), Instant.now());
        testEntityManager.flush();
        long generation = attemptExecutionRepository.claim(attempt.getId()).orElseThrow().generation();
        backdateClaim(attempt);

        assertThat(attemptExecutionRepository.resetStuck(attempt.getId(), generation, Duration.ofSeconds(60)))
                .isEqualTo(1);

        Attempt recovered = reload(attempt);
        assertThat(recovered.getStatus()).isEqualTo(AttemptStatus.CREATED);
        assertThat(recovered.getReadyPublishedAt()).isNull();
        assertThat(recovered.getReadyDispatchClaimId()).isNull();
        assertThat(recovered.getReadyDispatchClaimedAt()).isNull();
    }

    @Test
    void delayedOldConfirmCannotMarkRecoveredCreatedIncarnation() {
        Attempt attempt = createdAttempt();
        UUID oldClaim = UUID.randomUUID();
        readyWorkRepository.claimUnpublishedReady(oldClaim, Duration.ofSeconds(5), 1);
        long generation = attemptExecutionRepository.claim(attempt.getId()).orElseThrow().generation();
        backdateClaim(attempt);
        assertThat(attemptExecutionRepository.resetStuck(attempt.getId(), generation, Duration.ofSeconds(60)))
                .isEqualTo(1);

        UUID newClaim = UUID.randomUUID();
        assertThat(readyWorkRepository.claimUnpublishedReady(newClaim, Duration.ofSeconds(5), 1))
                .containsExactly(attempt.getId());

        assertThat(readyWorkRepository.markReadyPublished(attempt.getId(), oldClaim)).isZero();
        Attempt recovered = reload(attempt);
        assertThat(recovered.getReadyPublishedAt()).isNull();
        assertThat(recovered.getReadyDispatchClaimId()).isEqualTo(newClaim);
    }

    @Test
    void claimSkipsRowsLockedByIndependentTransaction() throws Exception {
        List<Attempt> attempts = createdAttempts(20);
        testEntityManager.flush();
        TestTransaction.flagForCommit();
        TestTransaction.end();

        List<UUID> lockedIds = jdbcTemplate.query(
                "SELECT id FROM attempts WHERE status = 'CREATED' AND ready_published_at IS NULL "
                        + "ORDER BY ready_dispatch_claimed_at NULLS FIRST, id LIMIT 10",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class));
        CountDownLatch locksHeld = new CountDownLatch(1);
        CountDownLatch releaseLocks = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> lockHolder = executor.submit(() -> holdRowsLocked(lockedIds, locksHeld, releaseLocks));
            assertThat(locksHeld.await(10, TimeUnit.SECONDS)).isTrue();

            Future<List<UUID>> unlockedClaim = executor.submit(
                    () -> readyWorkRepository.claimUnpublishedReady(UUID.randomUUID(), Duration.ofSeconds(5), 10));
            List<UUID> unlockedBatch;
            try {
                unlockedBatch = unlockedClaim.get(2, TimeUnit.SECONDS);
            } finally {
                releaseLocks.countDown();
            }
            lockHolder.get(10, TimeUnit.SECONDS);

            assertThat(unlockedBatch).hasSize(10);
            assertThat(unlockedBatch).doesNotContainAnyElementsOf(lockedIds);
            List<UUID> lockedBatch = readyWorkRepository.claimUnpublishedReady(
                    UUID.randomUUID(), Duration.ofSeconds(5), 10);
            assertThat(lockedBatch).hasSize(10);
            assertThat(lockedBatch).doesNotContainAnyElementsOf(unlockedBatch);
            Set<UUID> claimed = new HashSet<>(unlockedBatch);
            claimed.addAll(lockedBatch);
            assertThat(claimed).containsExactlyInAnyOrderElementsOf(attempts.stream().map(Attempt::getId).toList());
        } finally {
            releaseLocks.countDown();
            executor.shutdownNow();
        }
    }

    private void holdRowsLocked(List<UUID> lockedIds, CountDownLatch locksHeld, CountDownLatch releaseLocks) {
        transactionTemplate.executeWithoutResult(status -> {
            namedParameterJdbcTemplate.query(
                    "SELECT id FROM attempts WHERE id IN (:ids) FOR UPDATE",
                    new MapSqlParameterSource("ids", lockedIds),
                    (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class));
            locksHeld.countDown();
            try {
                if (!releaseLocks.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release test row locks");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while holding test row locks", e);
            }
        });
    }

    private Attempt createdAttempt() {
        return persistAttempt(AttemptStatus.CREATED, null);
    }

    private List<Attempt> createdAttempts(int count) {
        List<Attempt> attempts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            attempts.add(createdAttempt());
        }
        return attempts;
    }

    private Attempt scheduledAttempt(Instant nextRetryAt) {
        return persistAttempt(AttemptStatus.SCHEDULED, nextRetryAt);
    }

    private Attempt persistAttempt(AttemptStatus status, Instant nextRetryAt) {
        Endpoint endpoint = new Endpoint("endpoint-" + endpointSequence,
                "https://example.com/" + endpointSequence, "whsec_" + endpointSequence, app);
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 1);
        attempt.setStatus(status);
        attempt.setNextRetryAt(nextRetryAt);
        endpointSequence++;

        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);
        return attempt;
    }

    private void setReadyFields(Attempt attempt, Instant publishedAt, UUID claimId, Instant claimedAt) {
        attempt.setReadyPublishedAt(publishedAt);
        attempt.setReadyDispatchClaimId(claimId);
        attempt.setReadyDispatchClaimedAt(claimedAt);
    }

    private void backdateClaim(Attempt attempt) {
        jdbcTemplate.update(
                "UPDATE attempts SET execution_claimed_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE id = ?",
                attempt.getId());
    }

    private Attempt reload(Attempt attempt) {
        testEntityManager.clear();
        return attemptRepository.findById(attempt.getId()).orElseThrow();
    }
}
