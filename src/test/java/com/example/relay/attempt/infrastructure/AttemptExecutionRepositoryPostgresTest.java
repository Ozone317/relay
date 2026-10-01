package com.example.relay.attempt.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.example.relay.app.domain.App;
import com.example.relay.attempt.application.AttemptExecution;
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
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.dao.DataAccessException;

@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AttemptExecutionRepositoryImpl.class)
class AttemptExecutionRepositoryPostgresTest implements SharedPostgresContainer {
    @Autowired AttemptExecutionRepository repository;
    @Autowired TestEntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void createdAttemptStartsAtGenerationZeroAndClaimIncrementsWithDatabaseTime() throws Exception {
        Attempt attempt = createAttempt();
        assertEquals(0L, attempt.getExecutionGeneration());
        assertNull(attempt.getExecutionClaimedAt());
        em.flush();
        var first = repository.claim(attempt.getId()).orElseThrow();
        assertEquals(1L, first.generation());
        assertNotNull(first.claimedAt());
        assertTrue(repository.claim(attempt.getId()).isEmpty());
    }

    @Test
    void staleSelectionAndResetAreGenerationScopedAndUseDatabaseAge() throws Exception {
        Attempt attempt = createAttempt();
        em.flush();
        var claim = repository.claim(attempt.getId()).orElseThrow();
        jdbc.update("UPDATE attempts SET execution_claimed_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE id = ?", attempt.getId());
        List<AttemptExecutionCandidate> stale = repository.findStaleInFlight(Duration.ofMinutes(1), 10);
        assertTrue(stale.stream().anyMatch(c -> c.id().equals(attempt.getId()) && c.generation() == claim.generation()));
        assertEquals(1, repository.resetStuck(attempt.getId(), claim.generation(), Duration.ofMinutes(1)));
        assertEquals(0, repository.resetStuck(attempt.getId(), claim.generation(), Duration.ofMinutes(1)));
        var reset = jdbc.queryForMap("SELECT status, execution_claimed_at FROM attempts WHERE id = ?", attempt.getId());
        assertEquals("CREATED", reset.get("status"));
        assertNull(reset.get("execution_claimed_at"));
    }

    @Test
    void staleSelectionOrdersByClaimTimeThenIdAndHonorsTheBatchLimit() throws Exception {
        Attempt firstByTime = createAttempt();
        Attempt tiedA = createAttempt();
        Attempt tiedB = createAttempt();
        em.flush();
        repository.claim(firstByTime.getId()).orElseThrow();
        repository.claim(tiedA.getId()).orElseThrow();
        repository.claim(tiedB.getId()).orElseThrow();
        jdbc.update("UPDATE attempts SET execution_claimed_at = CASE id "
                + "WHEN ? THEN CURRENT_TIMESTAMP - INTERVAL '3 hours' "
                + "ELSE CURRENT_TIMESTAMP - INTERVAL '2 hours' END WHERE id IN (?, ?, ?)",
                firstByTime.getId(), firstByTime.getId(), tiedA.getId(), tiedB.getId());

        List<AttemptExecutionCandidate> all = repository.findStaleInFlight(Duration.ofMinutes(1), 10);
        List<AttemptExecutionCandidate> expected = all.stream()
                .sorted(java.util.Comparator.comparing(AttemptExecutionCandidate::claimedAt)
                        .thenComparing(candidate -> candidate.id().toString()))
                .toList();
        assertEquals(expected, all, "stale candidates must be ordered by claim timestamp and then ID");
        assertEquals(3, all.size());
        assertEquals(expected.subList(0, 2), repository.findStaleInFlight(Duration.ofMinutes(1), 2));
    }

    @Test
    void claimOverflowAtLongMax_rollsBackAndPreservesTheCreatedRow() throws Exception {
        Attempt attempt = createAttempt();
        em.flush();
        jdbc.update("UPDATE attempts SET execution_generation = ? WHERE id = ?", Long.MAX_VALUE, attempt.getId());
        TestTransaction.flagForCommit();
        TestTransaction.end();

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        assertThrows(DataAccessException.class,
                () -> transactions.execute(status -> repository.claim(attempt.getId())));
        var unchanged = transactions.execute(status -> jdbc.queryForMap(
                "SELECT status, execution_generation, execution_claimed_at FROM attempts WHERE id = ?",
                attempt.getId()));
        assertEquals("CREATED", unchanged.get("status"));
        assertEquals(Long.MAX_VALUE, ((Number) unchanged.get("execution_generation")).longValue());
        assertNull(unchanged.get("execution_claimed_at"));
    }

    @Test
    void fencedCompletionRequiresPositiveMatchingGenerationAndWritesDiagnostics() throws Exception {
        Attempt attempt = createAttempt();
        em.flush();
        var claim = repository.claim(attempt.getId()).orElseThrow();
        assertEquals(1,
                repository.markSucceeded(new AttemptExecution(attempt, claim.generation(), claim.claimedAt()), 204, "ok", 4L));
        var completed = jdbc.queryForMap("SELECT status, response_code, response_body, last_error, execution_claimed_at FROM attempts WHERE id = ?", attempt.getId());
        assertEquals("SUCCEEDED", completed.get("status"));
        assertEquals(204, completed.get("response_code"));
        assertNull(completed.get("last_error"));
        assertNull(completed.get("execution_claimed_at"));
        assertEquals(0,
                repository.markSucceeded(new AttemptExecution(attempt, 0L, claim.claimedAt()), 200, "stale", 1L));
    }

    @Test
    void markFailedRejectsUnsupportedStatusesAndOwnershipLossIsNotApplied() throws Exception {
        Attempt attempt = createAttempt();
        em.flush();
        var claim = repository.claim(attempt.getId()).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> repository.markFailed(
                new AttemptExecution(attempt, claim.generation(), claim.claimedAt()), AttemptStatus.IN_FLIGHT,
                null, 500, "bad", "error", 10L));
        assertEquals(0,
                repository.markFailed(new AttemptExecution(attempt, 0L, claim.claimedAt()), AttemptStatus.DEAD,
                        null, 500, "bad", "error", 10L));
        Instant dueAt = Instant.now().plusSeconds(30);
        assertEquals(1, repository.markFailed(
                new AttemptExecution(attempt, claim.generation(), claim.claimedAt()), AttemptStatus.FAILED_RETRYING,
                dueAt, 503, "retry response", "temporary failure", 12L));
        var failed = jdbc.queryForMap("SELECT status, next_retry_at, response_code, response_body, last_error, latency_ms, execution_claimed_at FROM attempts WHERE id = ?", attempt.getId());
        assertEquals("FAILED_RETRYING", failed.get("status"));
        assertEquals(503, failed.get("response_code"));
        assertEquals("retry response", failed.get("response_body"));
        assertEquals("temporary failure", failed.get("last_error"));
        assertEquals(12L, ((Number) failed.get("latency_ms")).longValue());
        assertNull(failed.get("execution_claimed_at"));
    }

    @Test
    void generationZeroIsRecoveryOnlyThenGenerationOneCompletes() throws Exception {
        Attempt attempt = createAttempt();
        em.flush();
        jdbc.update("UPDATE attempts SET status = 'IN_FLIGHT', execution_generation = 0, "
                + "execution_claimed_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE id = ?", attempt.getId());
        Instant legacyClaim = jdbc.queryForObject("SELECT execution_claimed_at FROM attempts WHERE id = ?",
                Instant.class, attempt.getId());
        var legacyExecution = new AttemptExecution(attempt, 0L, legacyClaim);
        assertEquals(0,
                repository.markSucceeded(legacyExecution, 200, "stale", 5L));
        assertEquals(0,
                repository.markFailed(legacyExecution, AttemptStatus.FAILED_RETRYING, Instant.now(),
                        500, "stale", "stale", 5L));
        assertEquals(1, repository.resetStuck(attempt.getId(), 0L, Duration.ofMinutes(1)));
        assertNull(jdbc.queryForObject("SELECT execution_claimed_at FROM attempts WHERE id = ?", Instant.class,
                attempt.getId()));
        var nextClaim = repository.claim(attempt.getId()).orElseThrow();
        assertEquals(1L, nextClaim.generation());
        assertTrue(nextClaim.claimedAt().isAfter(legacyClaim));
        assertEquals(1, repository.markSucceeded(
                new AttemptExecution(attempt, nextClaim.generation(), nextClaim.claimedAt()), 204, "ok", 7L));
        var completed = jdbc.queryForMap("SELECT status, response_code, response_body, last_error, latency_ms, execution_claimed_at FROM attempts WHERE id = ?", attempt.getId());
        assertEquals("SUCCEEDED", completed.get("status"));
        assertEquals(204, completed.get("response_code"));
        assertEquals("ok", completed.get("response_body"));
        assertNull(completed.get("last_error"));
        assertEquals(7L, ((Number) completed.get("latency_ms")).longValue());
        assertNull(completed.get("execution_claimed_at"));
    }

    private Attempt createAttempt() throws Exception {
        User user = new User("execution-" + java.util.UUID.randomUUID() + "@mail.com", "hash");
        Environment env = new Environment("Env", "Desc", user);
        App app = new App("App", env);
        Event event = new Event("event", app);
        Endpoint endpoint = new Endpoint("EP", "https://example.com", "secret", app);
        Message message = new Message(app, event, new ObjectMapper().readTree("{}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 1);
        em.persist(user); em.persist(env); em.persist(app); em.persist(event); em.persist(endpoint);
        em.persist(message); em.persist(delivery); em.persist(attempt);
        return attempt;
    }
}
