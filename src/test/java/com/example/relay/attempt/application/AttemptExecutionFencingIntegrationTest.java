package com.example.relay.attempt.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptExecutionRepository;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AttemptExecutionFencingIntegrationTest implements SharedPostgresContainer {
    @Autowired
    AttemptService attemptService;
    @Autowired
    AttemptRepository attemptRepository;
    @Autowired
    AttemptExecutionRepository executionRepository;
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

    @BeforeEach
    void clearRows() {
        attemptRepository.deleteAll();
        deliveryRepository.deleteAll();
        messageRepository.deleteAll();
        endpointRepository.deleteAll();
        eventRepository.deleteAll();
        appRepository.deleteAll();
        environmentRepository.deleteAll();
        userRepository.deleteAll();
    }

    @AfterEach
    void removeRows() {
        attemptRepository.deleteAll();
        deliveryRepository.deleteAll();
    }

    @Test
    void staleFailureAfterNewerSuccess_doesNotOverwrite() {
        Attempt attempt = createAttempt();
        AttemptExecution stale = claim(attempt.getId());
        reset(stale);
        AttemptExecution current = claim(attempt.getId());

        assertEquals(AttemptMutationOutcome.APPLIED, attemptService.markSucceeded(current, 204, "new success", 9L));
        assertEquals(AttemptMutationOutcome.OWNERSHIP_LOST,
                attemptService.markFailed(stale, AttemptStatus.DEAD, null, 503, "old response", "old error", 2L));

        assertRow(attempt.getId(), "SUCCEEDED", 2L, 204, "new success", null, null, 9L);
    }

    @Test
    void staleSuccessAfterNewerFailure_doesNotOverwrite() {
        Attempt attempt = createAttempt();
        AttemptExecution stale = claim(attempt.getId());
        reset(stale);
        AttemptExecution current = claim(attempt.getId());

        assertEquals(AttemptMutationOutcome.APPLIED,
                attemptService.markFailed(current, AttemptStatus.DEAD, null, 503, "new failure", "new error", 12L));
        assertEquals(AttemptMutationOutcome.OWNERSHIP_LOST,
                attemptService.markSucceeded(stale, 200, "old success", 1L));

        assertRow(attempt.getId(), "DEAD", 2L, 503, "new failure", "new error", null, 12L);
    }

    @Test
    void staleSuccessDuringCreatedGap_isRejected() {
        Attempt attempt = createAttempt();
        AttemptExecution stale = claim(attempt.getId());
        reset(stale);

        assertEquals(AttemptMutationOutcome.OWNERSHIP_LOST, attemptService.markSucceeded(stale, 200, "stale", 1L));
        assertRow(attempt.getId(), "CREATED", 1L, null, null, null, null, null);
    }

    @Test
    void staleGenerationAfterAba_isRejected() {
        Attempt attempt = createAttempt();
        AttemptExecution first = claim(attempt.getId());
        reset(first);
        AttemptExecution second = claim(attempt.getId());
        reset(second);
        AttemptExecution third = claim(attempt.getId());

        assertEquals(AttemptMutationOutcome.OWNERSHIP_LOST, attemptService.markSucceeded(first, 200, "stale", 1L));
        assertRow(attempt.getId(), "IN_FLIGHT", 3L, null, null, null, third.claimedAt(), null);
        assertEquals(third.claimedAt(), jdbc.queryForObject("SELECT execution_claimed_at FROM attempts WHERE id = ?",
                Instant.class, attempt.getId()));
    }

    @Test
    void activeGeneration_successAppliesOnce() {
        Attempt attempt = createAttempt();
        AttemptExecution execution = claim(attempt.getId());

        assertEquals(AttemptMutationOutcome.APPLIED, attemptService.markSucceeded(execution, 200, "success", 8L));
        assertEquals(AttemptMutationOutcome.OWNERSHIP_LOST,
                attemptService.markSucceeded(execution, 200, "duplicate", 1L));
        assertRow(attempt.getId(), "SUCCEEDED", 1L, 200, "success", null, null, 8L);
    }

    @Test
    void activeGeneration_finalDeadAppliesOnce() {
        Attempt attempt = createAttempt();
        AttemptExecution execution = claim(attempt.getId());

        assertEquals(AttemptMutationOutcome.APPLIED, attemptService.markFailed(execution, AttemptStatus.DEAD, null, 500,
                "final response", "final error", 15L));
        assertEquals(AttemptMutationOutcome.OWNERSHIP_LOST,
                attemptService.markFailed(execution, AttemptStatus.DEAD, null, 500, "duplicate", "duplicate", 1L));
        assertRow(attempt.getId(), "DEAD", 1L, 500, "final response", "final error", null, 15L);
    }

    @Test
    void staleFailureAfterNewerSuccess_createsNoRetry() {
        Attempt attempt = createAttempt();
        AttemptExecution stale = claim(attempt.getId());
        reset(stale);
        AttemptExecution current = claim(attempt.getId());

        assertEquals(AttemptMutationOutcome.APPLIED, attemptService.markSucceeded(current, 204, "new success", 9L));
        assertEquals(AttemptMutationOutcome.OWNERSHIP_LOST, attemptService.markFailedAndCreateRetry(stale,
                Instant.now().plusSeconds(30), 503, "old failure", "old error", 2L));

        assertEquals(1, attemptRepository.findAll().size());
        assertRow(attempt.getId(), "SUCCEEDED", 2L, 204, "new success", null, null, 9L);
    }

    @Test
    void staleSuccessAfterNewerFailureAndRetry_preservesParentAndChild() {
        Attempt attempt = createAttempt();
        AttemptExecution stale = claim(attempt.getId());
        reset(stale);
        AttemptExecution current = claim(attempt.getId());

        assertEquals(AttemptMutationOutcome.APPLIED, attemptService.markFailedAndCreateRetry(current,
                Instant.now().plusSeconds(30), 503, "new failure", "new error", 12L));
        assertEquals(AttemptMutationOutcome.OWNERSHIP_LOST,
                attemptService.markSucceeded(stale, 200, "old success", 1L));

        assertEquals(2, attemptRepository.findAll().size());
        assertRow(attempt.getId(), "FAILED_RETRYING", 2L, 503, "new failure", "new error", null, 12L);
        assertAttempt(attempt.getDelivery().getId(), 2, "SCHEDULED");
    }

    @Test
    void staleFailureAfterNewerRetry_createsNoDuplicateChild() {
        Attempt attempt = createAttempt();
        AttemptExecution stale = claim(attempt.getId());
        reset(stale);
        AttemptExecution current = claim(attempt.getId());

        assertEquals(AttemptMutationOutcome.APPLIED, attemptService.markFailedAndCreateRetry(current,
                Instant.now().plusSeconds(30), 503, "new failure", "new error", 12L));
        assertEquals(AttemptMutationOutcome.OWNERSHIP_LOST, attemptService.markFailedAndCreateRetry(stale,
                Instant.now().plusSeconds(60), 504, "old failure", "old error", 1L));

        assertEquals(2, attemptRepository.findAll().size());
        assertAttempt(attempt.getDelivery().getId(), 2, "SCHEDULED");
        assertRow(attempt.getId(), "FAILED_RETRYING", 2L, 503, "new failure", "new error", null, 12L);
    }

    @Test
    void staleCompletionAfterFirstChildTerminates_allocatesNoReplacementNumber() {
        Attempt attempt = createAttempt();
        AttemptExecution stale = claim(attempt.getId());
        reset(stale);
        AttemptExecution current = claim(attempt.getId());

        assertEquals(AttemptMutationOutcome.APPLIED, attemptService.markFailedAndCreateRetry(current,
                Instant.now().plusSeconds(30), 503, "new failure", "new error", 12L));
        Attempt retry = attemptRepository.findAll().stream().filter(candidate -> candidate.getAttemptNo() == 2)
                .findFirst().orElseThrow();
        jdbc.update("UPDATE attempts SET status = 'CREATED', next_retry_at = NULL WHERE id = ?", retry.getId());
        AttemptExecution retryExecution = claim(retry.getId());
        assertEquals(AttemptMutationOutcome.APPLIED,
                attemptService.markSucceeded(retryExecution, 204, "retry success", 8L));

        assertEquals(AttemptMutationOutcome.OWNERSHIP_LOST, attemptService.markFailedAndCreateRetry(stale,
                Instant.now().plusSeconds(60), 504, "old failure", "old error", 1L));

        assertEquals(1, countAttempts(attempt.getDelivery().getId(), 2));
        assertEquals(2, attemptRepository.findAll().size());
        assertEquals(List.of(1, 2),
                jdbc.queryForList("SELECT attempt_no FROM attempts WHERE delivery_id = ? ORDER BY attempt_no",
                        Integer.class, attempt.getDelivery().getId()));
        assertAttempt(attempt.getDelivery().getId(), 2, "SUCCEEDED");
        assertRow(attempt.getId(), "FAILED_RETRYING", 2L, 503, "new failure", "new error", null, 12L);
    }

    @Test
    void activeOwner_createsExactlyOneRetry() {
        Attempt attempt = createAttempt();
        AttemptExecution owner = claim(attempt.getId());

        assertEquals(AttemptMutationOutcome.APPLIED, attemptService.markFailedAndCreateRetry(owner,
                Instant.now().plusSeconds(30), 503, "failure", "error", 12L));
        assertEquals(AttemptMutationOutcome.OWNERSHIP_LOST, attemptService.markFailedAndCreateRetry(owner,
                Instant.now().plusSeconds(60), 504, "duplicate", "duplicate", 1L));

        assertEquals(2, attemptRepository.findAll().size());
        assertAttempt(attempt.getDelivery().getId(), 2, "SCHEDULED");
        assertRow(attempt.getId(), "FAILED_RETRYING", 1L, 503, "failure", "error", null, 12L);
    }

    private AttemptExecution claim(UUID attemptId) {
        return attemptService.claim(attemptId).orElseThrow();
    }

    private void reset(AttemptExecution execution) {
        jdbc.update("UPDATE attempts SET execution_claimed_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE id = ?",
                execution.attempt().getId());
        assertEquals(1, executionRepository.resetStuck(execution.attempt().getId(), execution.generation(),
                Duration.ofMinutes(1)));
    }

    private void assertRow(UUID attemptId, String status, long generation, Integer responseCode, String responseBody,
            String lastError, Instant claimedAt, Long latencyMs) {
        var row = jdbc.queryForMap("SELECT status, execution_generation, response_code, response_body, last_error, "
                + "execution_claimed_at, latency_ms, next_retry_at FROM attempts WHERE id = ?", attemptId);
        assertEquals(status, row.get("status"));
        assertEquals(generation, ((Number) row.get("execution_generation")).longValue());
        assertEquals(responseCode, row.get("response_code"));
        assertEquals(responseBody, row.get("response_body"));
        assertEquals(lastError, row.get("last_error"));
        if (claimedAt == null) {
            assertNull(row.get("execution_claimed_at"));
        } else {
            assertNotNull(row.get("execution_claimed_at"));
        }
        assertEquals(latencyMs, row.get("latency_ms") == null ? null : ((Number) row.get("latency_ms")).longValue());
        if ("FAILED_RETRYING".equals(status)) {
            assertNotNull(row.get("next_retry_at"));
        } else {
            assertNull(row.get("next_retry_at"));
        }
    }

    private void assertAttempt(UUID deliveryId, int attemptNo, String status) {
        var rows = jdbc.queryForList("SELECT status, execution_generation, execution_claimed_at FROM attempts "
                + "WHERE delivery_id = ? AND attempt_no = ?", deliveryId, attemptNo);
        assertEquals(1, rows.size());
        assertEquals(status, rows.get(0).get("status"));
        assertNull(rows.get(0).get("execution_claimed_at"));
        if ("SCHEDULED".equals(status)) {
            assertEquals(0L, ((Number) rows.get(0).get("execution_generation")).longValue());
        }
    }

    private int countAttempts(UUID deliveryId, int attemptNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM attempts WHERE delivery_id = ? AND attempt_no = ?",
                Integer.class, deliveryId, attemptNo);
    }

    private Attempt createAttempt() {
        User user = userRepository.save(new User("fencing-" + UUID.randomUUID() + "@example.com", "hash"));
        Environment environment = environmentRepository.save(new Environment("Test", "Test", user));
        App app = appRepository.save(new App("Test", environment));
        Event event = eventRepository.save(new Event("fencing.test", app));
        Endpoint endpoint = endpointRepository.save(new Endpoint("Test", "https://example.com", "secret", app));
        Message message = messageRepository.save(new Message(app, event, new ObjectMapper().createObjectNode()));
        Delivery delivery = deliveryRepository.save(new Delivery(app, message, endpoint));
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 1);
        return attemptRepository.save(attempt);
    }
}
