package com.example.relay.attempt.application;

import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.message.domain.Message;
import com.example.relay.subscription.domain.Subscription;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttemptService {

    private final AttemptRepository attemptRepository;

    public AttemptService(AttemptRepository attemptRepository) {
        this.attemptRepository = attemptRepository;
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
            Attempt attempt = new Attempt(sub.getApp(), message, sub.getEndpoint(), 1);
            attempts.add(attempt);
        }

        return attempts;
    }

    @Transactional
    public boolean claim(UUID attemptId) {
        return attemptRepository.claim(attemptId) == 1;
    }

    @Transactional
    public Attempt createRetry(Attempt previous, Instant nextRetryAt) {
        Attempt retry = new Attempt(previous.getApp(), previous.getMessage(), previous.getEndpoint(),
                previous.getAttemptNo() + 1);
        retry.setStatus(AttemptStatus.SCHEDULED);
        retry.setNextRetryAt(nextRetryAt);
        return attemptRepository.save(retry);
    }

    @Transactional
    public Attempt markSucceeded(Attempt attempt, Integer responseCode, String responseBody, Long latencyMs) {
        attempt.setResponseCode(responseCode);
        attempt.setResponseBody(responseBody);
        attempt.setLatencyMs(latencyMs);
        attempt.setStatus(AttemptStatus.SUCCEEDED);

        return attemptRepository.save(attempt);
    }

    @Transactional
    public Attempt markFailed(Attempt attempt, AttemptStatus status, Instant nextRetryAt, Integer responseCode,
            String responseBody, String lastError, Long latencyMs) {
        attempt.setStatus(status);
        attempt.setNextRetryAt(nextRetryAt);
        attempt.setResponseCode(responseCode);
        attempt.setResponseBody(truncate(responseBody, 10240));
        attempt.setLastError(truncate(lastError, 10240));
        attempt.setLatencyMs(latencyMs);

        return attemptRepository.save(attempt);
    }

    @Transactional
    public int resetStuck(UUID attemptId, Instant threshold) {
        return attemptRepository.resetStuck(attemptId, threshold);
    }

    @Transactional
    public int resetScheduled(UUID attemptId, Instant threshold) {
        return attemptRepository.resetScheduled(attemptId, threshold);
    }

    @Transactional
    public int touchCreated(UUID attemptId) {
        return attemptRepository.touchCreated(attemptId);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }

        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
