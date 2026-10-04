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

public interface AttemptRepository extends JpaRepository<Attempt, UUID> {

    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE attempts
                SET status = 'IN_FLIGHT', execution_generation = execution_generation + 1,
                    execution_claimed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = :attemptId
                AND status = 'CREATED'
            """, nativeQuery = true)
    int claim(UUID attemptId, Instant now);

    List<Attempt> findByStatusAndNextRetryAtBefore(AttemptStatus status, Instant threshold, Limit limit);

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

    boolean existsByMessageIdAndEndpointIdAndStatusIn(UUID messageId, UUID endpointId, List<AttemptStatus> statuses);

    Page<Attempt> findByDeliveryId(UUID deliveryId, Pageable pageable);

    Optional<Attempt> findByIdAndDeliveryIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(UUID attemptId,
            UUID deliveryId, UUID appId, UUID environmentId, UUID userId);
}
