package com.example.relay.attempt.infrastructure;

import com.example.relay.attempt.application.AttemptExecution;
import com.example.relay.attempt.application.AttemptMutationOutcome;
import com.example.relay.attempt.domain.AttemptStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttemptExecutionRepository {
    Optional<AttemptExecutionClaim> claim(UUID attemptId);

    List<AttemptExecutionCandidate> findStaleInFlight(Duration grace, int limit);

    int resetStuck(UUID attemptId, long observedGeneration, Duration grace);

    AttemptMutationOutcome markSucceeded(AttemptExecution execution, Integer responseCode, String responseBody,
            Long latencyMs);

    AttemptMutationOutcome markFailed(AttemptExecution execution, AttemptStatus status, Instant nextRetryAt,
            Integer responseCode, String responseBody, String lastError, Long latencyMs);
}
