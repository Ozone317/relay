package com.example.relay.attempt.infrastructure;

import com.example.relay.attempt.application.AttemptExecution;
import com.example.relay.attempt.domain.AttemptStatus;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AttemptExecutionRepositoryImpl implements AttemptExecutionRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public AttemptExecutionRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AttemptExecutionClaim> claim(UUID attemptId) {
        var parameters = new MapSqlParameterSource("attemptId", attemptId);
        List<AttemptExecutionClaim> claims = jdbc.query("""
                UPDATE attempts
                SET status = 'IN_FLIGHT',
                    execution_generation = execution_generation + 1,
                    execution_claimed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :attemptId
                  AND status = 'CREATED'
                RETURNING execution_generation, execution_claimed_at
                """, parameters, (rs, row) -> new AttemptExecutionClaim(rs.getLong("execution_generation"),
                rs.getTimestamp("execution_claimed_at").toInstant()));
        return claims.stream().findFirst();
    }

    @Override
    public List<AttemptExecutionCandidate> findStaleInFlight(Duration grace, int limit) {
        return jdbc.query("""
                SELECT id, execution_generation, execution_claimed_at
                FROM attempts
                WHERE status = 'IN_FLIGHT'
                  AND execution_claimed_at < CURRENT_TIMESTAMP - (:graceMillis * INTERVAL '1 millisecond')
                ORDER BY execution_claimed_at, id
                LIMIT :limit
                """, new MapSqlParameterSource().addValue("graceMillis", grace.toMillis()).addValue("limit", limit),
                (rs, row) -> new AttemptExecutionCandidate(rs.getObject("id", UUID.class),
                        rs.getLong("execution_generation"), rs.getTimestamp("execution_claimed_at").toInstant()));
    }

    @Override
    public int resetStuck(UUID attemptId, long observedGeneration, Duration grace) {
        return jdbc.update("""
                UPDATE attempts
                SET status = 'CREATED',
                    execution_claimed_at = NULL,
                    ready_published_at = NULL,
                    ready_dispatch_claim_id = NULL,
                    ready_dispatch_claimed_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :attemptId
                  AND status = 'IN_FLIGHT'
                  AND execution_generation = :observedGeneration
                  AND execution_claimed_at < CURRENT_TIMESTAMP - (:graceMillis * INTERVAL '1 millisecond')
                """, new MapSqlParameterSource().addValue("attemptId", attemptId)
                .addValue("observedGeneration", observedGeneration).addValue("graceMillis", grace.toMillis()));
    }

    @Override
    public int markSucceeded(AttemptExecution execution, Integer responseCode, String responseBody,
            Long latencyMs) {
        return jdbc.update("""
                UPDATE attempts
                SET status = 'SUCCEEDED',
                    next_retry_at = NULL,
                    response_code = :responseCode,
                    response_body = :responseBody,
                    last_error = NULL,
                    latency_ms = :latencyMs,
                    execution_claimed_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :attemptId
                  AND status = 'IN_FLIGHT'
                  AND execution_generation = :generation
                  AND execution_generation > 0
                """, completionParameters(execution, responseCode, responseBody, latencyMs));
    }

    @Override
    public int markFailed(AttemptExecution execution, AttemptStatus status, Instant nextRetryAt,
            Integer responseCode, String responseBody, String lastError, Long latencyMs) {
        if (status != AttemptStatus.FAILED_RETRYING && status != AttemptStatus.DEAD) {
            throw new IllegalArgumentException("Failed Attempt status must be FAILED_RETRYING or DEAD");
        }
        return jdbc.update("""
                UPDATE attempts
                SET status = :status,
                    next_retry_at = :nextRetryAt,
                    response_code = :responseCode,
                    response_body = :responseBody,
                    last_error = :lastError,
                    latency_ms = :latencyMs,
                    execution_claimed_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :attemptId
                  AND status = 'IN_FLIGHT'
                  AND execution_generation = :generation
                  AND execution_generation > 0
                """, completionParameters(execution, responseCode, responseBody, latencyMs)
                .addValue("status", status.name())
                .addValue("nextRetryAt", status == AttemptStatus.DEAD || nextRetryAt == null
                        ? null
                        : Timestamp.from(nextRetryAt))
                .addValue("lastError", lastError));
    }

    private MapSqlParameterSource completionParameters(AttemptExecution execution, Integer responseCode,
            String responseBody, Long latencyMs) {
        return new MapSqlParameterSource().addValue("attemptId", execution.attempt().getId())
                .addValue("generation", execution.generation()).addValue("responseCode", responseCode)
                .addValue("responseBody", responseBody).addValue("latencyMs", latencyMs);
    }
}
