package com.example.relay.attempt.infrastructure;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReadyWorkRepositoryImpl implements ReadyWorkRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ReadyWorkRepositoryImpl(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public List<UUID> promoteDueScheduled(int batchSize) {
        String sql = """
                WITH due AS (
                  SELECT id FROM attempts
                  WHERE status = 'SCHEDULED' AND next_retry_at <= CURRENT_TIMESTAMP
                  ORDER BY next_retry_at, id
                  FOR UPDATE SKIP LOCKED LIMIT :batchSize
                )
                UPDATE attempts a
                SET status = 'CREATED', ready_published_at = NULL,
                    ready_dispatch_claim_id = NULL, ready_dispatch_claimed_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                FROM due WHERE a.id = due.id
                RETURNING a.id
                """;
        return jdbcTemplate.query(sql, parameters().addValue("batchSize", batchSize),
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class));
    }

    @Override
    @Transactional
    public List<UUID> claimUnpublishedReady(UUID claimId, Duration grace, int batchSize) {
        String sql = """
                WITH candidates AS (
                  SELECT id FROM attempts
                  WHERE status = 'CREATED' AND ready_published_at IS NULL
                    AND (ready_dispatch_claimed_at IS NULL
                      OR ready_dispatch_claimed_at <= CURRENT_TIMESTAMP - (:graceMillis * INTERVAL '1 millisecond'))
                  ORDER BY ready_dispatch_claimed_at NULLS FIRST, id
                  FOR UPDATE SKIP LOCKED LIMIT :batchSize
                )
                UPDATE attempts a
                SET ready_dispatch_claim_id = :claimId,
                    ready_dispatch_claimed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                FROM candidates c WHERE a.id = c.id
                RETURNING a.id
                """;
        MapSqlParameterSource parameters = parameters()
                .addValue("claimId", claimId)
                .addValue("graceMillis", grace.toMillis())
                .addValue("batchSize", batchSize);
        return jdbcTemplate.query(sql, parameters, (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class));
    }

    @Override
    @Transactional
    public int markReadyPublished(UUID attemptId, UUID claimId) {
        String sql = """
                UPDATE attempts
                SET ready_published_at = CURRENT_TIMESTAMP,
                    ready_dispatch_claim_id = NULL, ready_dispatch_claimed_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :attemptId AND status = 'CREATED'
                  AND ready_published_at IS NULL
                  AND ready_dispatch_claim_id = :claimId
                """;
        return jdbcTemplate.update(sql, parameters()
                .addValue("attemptId", attemptId)
                .addValue("claimId", claimId));
    }

    private MapSqlParameterSource parameters() {
        return new MapSqlParameterSource();
    }
}
