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
    }

    private void recoverCreated() {
        List<Attempt> attempts = attemptRepository.findByStatusAndUpdatedAtBefore(
            AttemptStatus.CREATED,
            Instant.now().minus(reconciliationProperties.getCreatedGrace()),
            Limit.of(reconciliationProperties.getBatchSize())
        );

        for (Attempt attempt : attempts) {
            if (attemptService.touchCreated(attempt.getId()) == 1) {
                attemptPublisher.publish(attempt.getId());
            }
        }
    }

    private void recoverInFlight() {
        Instant threshold = Instant.now().minus(reconciliationProperties.getInFlightGrace());
        List<Attempt> attempts = attemptRepository.findByStatusAndUpdatedAtBefore(
            AttemptStatus.IN_FLIGHT, threshold, Limit.of(reconciliationProperties.getBatchSize())
        );

        for (Attempt attempt : attempts) {
            if (attemptService.resetStuck(attempt.getId(), threshold) == 1) {
                log.warn("Reset stuck IN_FLIGHT attempt {} back to CREATED", attempt.getId());
                attemptPublisher.publish(attempt.getId());
            } else {
                log.info("Attempt {} resolved before the sweep could reset it, skipping", attempt.getId());
            }
        }
    }

    private void recoverScheduled() {
        Instant threshold = Instant.now().minus(reconciliationProperties.getScheduledSlack());
        List<Attempt> attempts = attemptRepository.findByStatusAndNextRetryAtBefore(
            AttemptStatus.SCHEDULED, threshold, Limit.of(reconciliationProperties.getBatchSize())
        );

        for (Attempt attempt : attempts) {
            if (attemptService.resetScheduled(attempt.getId(), threshold) == 1) {
                log.warn("Recovered overdue SCHEDULED attempt {} to CREATED", attempt.getId());
                attemptPublisher.publish(attempt.getId());
            } else {
                log.info("Attempt {} resolved before the sweep could recover it, skipping", attempt.getId());
            }
        }
    }
}
