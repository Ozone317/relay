package com.example.relay.attempt.application;

import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptExecutionClaim;
import com.example.relay.attempt.infrastructure.AttemptExecutionRepository;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.message.domain.Message;
import com.example.relay.subscription.domain.Subscription;

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

    private final AttemptRepository attemptRepository;
    private final AttemptExecutionRepository executionRepository;
    private final DeliveryRepository deliveryRepository;

    public AttemptService(AttemptRepository attemptRepository, AttemptExecutionRepository executionRepository,
            DeliveryRepository deliveryRepository) {
        this.attemptRepository = attemptRepository;
        this.executionRepository = executionRepository;
        this.deliveryRepository = deliveryRepository;
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
    public Attempt createReplay(Attempt original) {
        Attempt replay = new Attempt(original.getApp(), original.getMessage(), original.getEndpoint(),
                original.getDelivery(), original.getAttemptNo() + 1);
        return attemptRepository.saveAndFlush(replay);
    }

    @Transactional
    public AttemptMutationOutcome markSucceeded(AttemptExecution execution, Integer responseCode, String responseBody,
            Long latencyMs) {
        AttemptMutationOutcome outcome = executionRepository.markSucceeded(execution, responseCode,
                truncate(responseBody, DIAGNOSTIC_CHARACTER_LIMIT), latencyMs);
        return checkedOutcome(outcome);
    }

    @Transactional
    public AttemptMutationOutcome markFailed(AttemptExecution execution, AttemptStatus status, Instant nextRetryAt,
            Integer responseCode, String responseBody, String lastError, Long latencyMs) {
        AttemptMutationOutcome outcome = executionRepository.markFailed(execution, status, nextRetryAt, responseCode,
                truncate(responseBody, DIAGNOSTIC_CHARACTER_LIMIT), truncate(lastError, DIAGNOSTIC_CHARACTER_LIMIT),
                latencyMs);
        return checkedOutcome(outcome);
    }

    @Transactional
    public AttemptMutationOutcome markFailedAndCreateRetry(AttemptExecution execution, Instant nextRetryAt,
            Integer responseCode, String responseBody, String lastError, Long latencyMs) {
        AttemptMutationOutcome outcome = markFailed(execution, AttemptStatus.FAILED_RETRYING, nextRetryAt, responseCode,
                responseBody, lastError, latencyMs);
        if (outcome == AttemptMutationOutcome.OWNERSHIP_LOST) {
            return outcome;
        }
        // Explicit flush: Hibernate's default flush ordering runs every queued INSERT before any
        // queued UPDATE in the same flush, regardless of Java call order. Without this, the new
        // retry row's INSERT would hit idx_attempts_one_active_per_message_endpoint while the row
        // above is still (from the DB's perspective) active, since its UPDATE hasn't executed yet.
        attemptRepository.flush();
        createRetry(execution.attempt(), nextRetryAt);
        return outcome;
    }

    @Transactional
    public int resetStuck(UUID attemptId, Instant threshold, Instant now) {
        return attemptRepository.resetStuck(attemptId, threshold, now);
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

    private AttemptMutationOutcome checkedOutcome(AttemptMutationOutcome outcome) {
        if (outcome == null) {
            throw new IllegalStateException("Attempt execution repository returned no mutation outcome");
        }
        return outcome;
    }
}
