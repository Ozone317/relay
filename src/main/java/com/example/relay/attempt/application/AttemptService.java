package com.example.relay.attempt.application;

import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptAllocationRepository;
import com.example.relay.attempt.infrastructure.AttemptExecutionClaim;
import com.example.relay.attempt.infrastructure.AttemptExecutionRepository;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.exception.ActiveAttemptAlreadyExistsException;
import com.example.relay.delivery.exception.DeliveryNotDeadException;
import com.example.relay.delivery.exception.DeliveryNotFoundException;
import com.example.relay.delivery.exception.ReplayEndpointInactiveException;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.message.domain.Message;
import com.example.relay.subscription.domain.Subscription;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttemptService {

    private static final int DIAGNOSTIC_CHARACTER_LIMIT = 10_240;
    private static final List<AttemptStatus> ACTIVE_STATUSES =
            List.of(AttemptStatus.CREATED, AttemptStatus.IN_FLIGHT, AttemptStatus.SCHEDULED);

    private final AttemptRepository attemptRepository;
    private final AttemptExecutionRepository executionRepository;
    private final DeliveryRepository deliveryRepository;
    private final AttemptAllocationRepository allocationRepository;

    public AttemptService(AttemptRepository attemptRepository, AttemptExecutionRepository executionRepository,
            DeliveryRepository deliveryRepository, AttemptAllocationRepository allocationRepository) {
        this.attemptRepository = attemptRepository;
        this.executionRepository = executionRepository;
        this.deliveryRepository = deliveryRepository;
        this.allocationRepository = allocationRepository;
    }

    @Transactional
    public List<Attempt> createFromSubscriptionList(List<Subscription> susbcriptions, Message message) {
        List<Attempt> attempts = createAttempts(susbcriptions, message);
        List<Attempt> createdAttempts = attemptRepository.saveAll(attempts);
        return createdAttempts;
    }

    private List<Attempt> createAttempts(List<Subscription> subscriptions, Message message) {
        List<Attempt> attempts = new ArrayList<>();
        for (Subscription sub : subscriptions) {
            // One Delivery per (message, endpoint) pair, created exactly once here at fan-out
            // time, in the same transaction as this pair's first Attempt - see
            // docs/superpowers/specs/2026-09-09-delivery-entity-design.md Section 4. Automatic
            // retries and manual replays never create another Delivery; they carry this one
            // forward (see createRetry/createReplay below).
            Delivery delivery = deliveryRepository.save(new Delivery(sub.getApp(), message, sub.getEndpoint()));
            Attempt attempt = new Attempt(sub.getApp(), message, sub.getEndpoint(), delivery, 1);
            attempts.add(attempt);
        }

        return attempts;
    }

    @Transactional
    public Optional<AttemptExecution> claim(UUID attemptId) {
        Optional<AttemptExecutionClaim> claim = executionRepository.claim(attemptId);
        if (claim.isEmpty()) {
            return Optional.empty();
        }
        Attempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalStateException("Claimed attempt " + attemptId + " not found"));
        return Optional.of(new AttemptExecution(attempt, claim.get().generation(), claim.get().claimedAt()));
    }

    @Transactional
    public Attempt createRetry(Attempt previous, Instant nextRetryAt) {
        Attempt retry = new Attempt(previous.getApp(), previous.getMessage(), previous.getEndpoint(),
                previous.getDelivery(), previous.getAttemptNo() + 1);
        retry.setStatus(AttemptStatus.SCHEDULED);
        retry.setNextRetryAt(nextRetryAt);
        return attemptRepository.save(retry);
    }

    @Transactional
    public Attempt createReplay(Delivery delivery) {
        boolean parentsLocked = allocationRepository
                .lockReplayAllocationParentsIfEndpointActive(delivery.getEndpoint().getId(), delivery.getId());
        if (!parentsLocked) {
            throw new ReplayEndpointInactiveException(delivery.getEndpoint().getId());
        }
        Attempt latest = attemptRepository.findFirstByDeliveryIdOrderByAttemptNoDesc(delivery.getId())
                .orElseThrow(() -> new DeliveryNotFoundException(delivery.getId()));
        if (latest.getStatus() != AttemptStatus.DEAD) {
            throw new DeliveryNotDeadException(delivery.getId(), latest.getStatus());
        }
        if (attemptRepository.existsByMessageIdAndEndpointIdAndStatusIn(delivery.getMessage().getId(),
                delivery.getEndpoint().getId(), ACTIVE_STATUSES)) {
            throw new ActiveAttemptAlreadyExistsException(delivery.getMessage().getId(),
                    delivery.getEndpoint().getId());
        }
        int attemptNo = allocationRepository.nextAttemptNoUnderDeliveryLock(delivery.getId());
        return attemptRepository.saveAndFlush(
                new Attempt(delivery.getApp(), delivery.getMessage(), delivery.getEndpoint(), delivery, attemptNo));
    }

    @Transactional
    public AttemptMutationOutcome markSucceeded(AttemptExecution execution, Integer responseCode, String responseBody,
            Long latencyMs) {
        int rows = executionRepository.markSucceeded(execution, responseCode,
                truncate(responseBody, DIAGNOSTIC_CHARACTER_LIMIT), latencyMs);
        return checkedOutcome(rows);
    }

    @Transactional
    public AttemptMutationOutcome markFailed(AttemptExecution execution, AttemptStatus status, Instant nextRetryAt,
            Integer responseCode, String responseBody, String lastError, Long latencyMs) {
        int rows = executionRepository.markFailed(execution, status, nextRetryAt, responseCode,
                truncate(responseBody, DIAGNOSTIC_CHARACTER_LIMIT), truncate(lastError, DIAGNOSTIC_CHARACTER_LIMIT),
                latencyMs);
        return checkedOutcome(rows);
    }

    @Transactional
    public AttemptMutationOutcome markFailedAndCreateRetry(AttemptExecution execution, Instant nextRetryAt,
            Integer responseCode, String responseBody, String lastError, Long latencyMs) {
        int updated = executionRepository.markFailed(execution, AttemptStatus.FAILED_RETRYING, nextRetryAt,
                responseCode, truncate(responseBody, DIAGNOSTIC_CHARACTER_LIMIT),
                truncate(lastError, DIAGNOSTIC_CHARACTER_LIMIT), latencyMs);
        if (updated == 0) {
            return AttemptMutationOutcome.OWNERSHIP_LOST;
        }
        if (updated != 1) {
            throw new IllegalStateException("unexpected parent update count: " + updated);
        }
        createRetry(execution.attempt(), nextRetryAt);
        return AttemptMutationOutcome.APPLIED;
    }

    @Transactional
    public int resetStuck(UUID attemptId, long observedGeneration, Duration grace) {
        return executionRepository.resetStuck(attemptId, observedGeneration, grace);
    }

    @Transactional
    public int touchDeadLetterCandidate(UUID attemptId, Instant threshold, Instant now) {
        return attemptRepository.touchDeadLetterCandidate(attemptId, threshold, now);
    }

    @Transactional
    public boolean claimDeadLetterNotification(UUID attemptId, Instant now) {
        return attemptRepository.claimDeadLetterNotification(attemptId, now) == 1;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }

        if (value.length() <= maxLength) {
            return value;
        }

        int end = maxLength;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private AttemptMutationOutcome checkedOutcome(int rows) {
        if (rows == 1) {
            return AttemptMutationOutcome.APPLIED;
        }
        if (rows == 0) {
            return AttemptMutationOutcome.OWNERSHIP_LOST;
        }
        throw new IllegalStateException("Attempt execution repository updated unexpected row count: " + rows);
    }
}
