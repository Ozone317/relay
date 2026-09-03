package com.example.relay.delivery.reconciliation;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.config.RabbitMqConfig;
import com.example.relay.delivery.publisher.AttemptPublisher;

@Component
public class ReconciliationSweeper {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationSweeper.class);

    private AttemptRepository attemptRepository;
    private AttemptPublisher attemptPublisher;
    private AttemptService attemptService;
    private ReconciliationProperties reconciliationProperties;

    public ReconciliationSweeper(
        AttemptRepository attemptRepository,
        AttemptPublisher attemptPublisher,
        AttemptService attemptService,
        ReconciliationProperties reconciliationProperties
    ) {
        this.attemptRepository = attemptRepository;
        this.attemptPublisher = attemptPublisher;
        this.attemptService = attemptService;
        this.reconciliationProperties = reconciliationProperties;
    }

    @Scheduled(fixedDelayString = "${relay.reconciliation.interval}")
    public void sweep() {
        recoverCreated();
        recoverInFlight();
        recoverScheduled();
        recoverDeadLetter();
    }

    private void recoverCreated() {
        Instant now = Instant.now();
        Instant threshold = now.minus(reconciliationProperties.getCreatedGrace());
        List<Attempt> attempts = attemptRepository.findByStatusAndUpdatedAtBefore(
            AttemptStatus.CREATED, threshold, Limit.of(reconciliationProperties.getBatchSize())
        );

        for (Attempt attempt : attempts) {
            if (attemptService.touchCreated(attempt.getId(), now) == 1) {
                log.warn("Republishing stale CREATED attempt {} to delivery.tasks", attempt.getId());
                attemptPublisher.publish(attempt.getId());
            } else {
                log.info("Attempt {} moved on before the sweep could republish it, skipping", attempt.getId());
            }
        }
    }

    private void recoverInFlight() {
        Instant now = Instant.now();
        Instant threshold = now.minus(reconciliationProperties.getInFlightGrace());
        List<Attempt> attempts = attemptRepository.findByStatusAndUpdatedAtBefore(
            AttemptStatus.IN_FLIGHT, threshold, Limit.of(reconciliationProperties.getBatchSize())
        );

        for (Attempt attempt : attempts) {
            if (attemptService.resetStuck(attempt.getId(), threshold, now) == 1) {
                log.warn("Reset stuck IN_FLIGHT attempt {} back to CREATED", attempt.getId());
                attemptPublisher.publish(attempt.getId());
            } else {
                log.info("Attempt {} resolved before the sweep could reset it, skipping", attempt.getId());
            }
        }
    }

    private void recoverScheduled() {
        Instant now = Instant.now();
        Instant threshold = now.minus(reconciliationProperties.getScheduledSlack());
        List<Attempt> attempts = attemptRepository.findByStatusAndNextRetryAtBefore(
            AttemptStatus.SCHEDULED, threshold, Limit.of(reconciliationProperties.getBatchSize())
        );

        for (Attempt attempt : attempts) {
            if (attemptService.resetScheduled(attempt.getId(), threshold, now) == 1) {
                log.warn("Recovered overdue SCHEDULED attempt {} to CREATED", attempt.getId());
                attemptPublisher.publish(attempt.getId());
            } else {
                log.info("Attempt {} resolved before the sweep could recover it, skipping", attempt.getId());
            }
        }
    }

    private void recoverDeadLetter() {
        Instant now = Instant.now();
        Instant threshold = now.minus(reconciliationProperties.getDeadLetterGrace());
        List<Attempt> attempts = attemptRepository.findByStatusAndDeadLetterNotifiedAtIsNullAndUpdatedAtBefore(
            AttemptStatus.DEAD, threshold, Limit.of(reconciliationProperties.getBatchSize())
        );

        for (Attempt attempt : attempts) {
            if (attemptService.touchDeadLetterCandidate(attempt.getId(), threshold, now) == 1) {
                log.warn("Republishing unnotified DEAD attempt {} to delivery.deadletter", attempt.getId());
                attemptPublisher.publishToRoutingKey(attempt.getId(), RabbitMqConfig.DEADLETTER_ROUTING_KEY);
            } else {
                log.info("Attempt {} was notified or already re-touched before the sweep could republish it, skipping",
                        attempt.getId());
            }
        }
    }
}
