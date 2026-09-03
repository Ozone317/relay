package com.example.relay.attempt.infrastructure;

import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AttemptRepository extends JpaRepository<Attempt, UUID> {

    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE attempts
                SET status = 'IN_FLIGHT', updated_at = now()
                WHERE id = :attemptId
                AND status IN ('CREATED', 'SCHEDULED')
            """, nativeQuery = true)
    int claim(UUID attemptId);

    List<Attempt> findByStatusAndUpdatedAtBefore(AttemptStatus status, Instant threshold, Limit limit);

    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE attempts
                SET status = 'CREATED', updated_at = now()
                WHERE id = :attemptId
                AND status = 'IN_FLIGHT'
                AND updated_at < :threshold
            """, nativeQuery = true)
    int resetStuck(UUID attemptId, Instant threshold);

    List<Attempt> findByStatusAndNextRetryAtBefore(AttemptStatus status, Instant threshold, Limit limit);

    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE attempts
                SET status = 'CREATED', updated_at = now()
                WHERE id = :attemptId
                AND status = 'SCHEDULED'
                AND next_retry_at < :threshold
            """, nativeQuery = true)
    int resetScheduled(UUID attemptId, Instant threshold);
}
