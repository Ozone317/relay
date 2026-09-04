package com.example.relay.attempt.infrastructure;

import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttemptRepository extends JpaRepository<Attempt, UUID> {

    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE attempts
                SET status = 'IN_FLIGHT', updated_at = :now
                WHERE id = :attemptId
                AND status IN ('CREATED', 'SCHEDULED')
            """, nativeQuery = true)
    int claim(UUID attemptId, Instant now);

    List<Attempt> findByStatusAndUpdatedAtBefore(AttemptStatus status, Instant threshold, Limit limit);

    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE attempts
                SET status = 'CREATED', updated_at = :now
                WHERE id = :attemptId
                AND status = 'IN_FLIGHT'
                AND updated_at < :threshold
            """, nativeQuery = true)
    int resetStuck(UUID attemptId, Instant threshold, Instant now);

    List<Attempt> findByStatusAndNextRetryAtBefore(AttemptStatus status, Instant threshold, Limit limit);

    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE attempts
                SET status = 'CREATED', updated_at = :now
                WHERE id = :attemptId
                AND status = 'SCHEDULED'
                AND next_retry_at < :threshold
            """, nativeQuery = true)
    int resetScheduled(UUID attemptId, Instant threshold, Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE attempts
                SET updated_at = :now
                WHERE id = :attemptId
                AND status = 'CREATED'
            """, nativeQuery = true)
    int touchCreated(UUID attemptId, Instant now);

    List<Attempt> findByStatusAndDeadLetterNotifiedAtIsNullAndUpdatedAtBefore(AttemptStatus status, Instant threshold,
            Limit limit);

    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE attempts
                SET updated_at = :now
                WHERE id = :attemptId
                AND status = 'DEAD'
                AND dead_letter_notified_at IS NULL
                AND updated_at < :threshold
            """, nativeQuery = true)
    int touchDeadLetterCandidate(UUID attemptId, Instant threshold, Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE attempts
                SET dead_letter_notified_at = :now
                WHERE id = :attemptId
                AND dead_letter_notified_at IS NULL
            """, nativeQuery = true)
    int claimDeadLetterNotification(UUID attemptId, Instant now);

    @Query(
        """
            SELECT a FROM Attempt a
            WHERE a.app.id = :appId
            AND (:endpointId IS NULL OR a.endpoint.id = :endpointId)    
            AND (:status IS NULL OR a.status = :status)
            AND (:createdFrom IS NULL OR a.createdAt >= :createdFrom)
            AND (:createdTo IS NULL OR a.createdAt <= :createdTo)
        """
    )
    Page<Attempt> findByAppIdAndFilters(
        @Param("appId") UUID appId,
        @Param("endpointId") UUID endpointId,
        @Param("status") AttemptStatus status,
        @Param("createdFrom") Instant createdFrom,
        @Param("createdTo") Instant createdTo,
        Pageable pageable
    );

    Optional<Attempt> findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(
        UUID attemptId,
        UUID appId,
        UUID environmentId,
        UUID userId
    );
}
