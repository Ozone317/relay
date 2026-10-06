package com.example.relay.deliveryengine.reconciliation;

import java.time.Instant;
import java.util.List;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.relay.common.scheduling.SchedulerNames;
import com.example.relay.common.scheduling.ScheduledJobMetrics;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.attempt.infrastructure.AttemptExecutionCandidate;
import com.example.relay.attempt.infrastructure.AttemptExecutionRepository;
import com.example.relay.deliveryengine.config.RabbitMqConfig;
import com.example.relay.deliveryengine.publisher.AttemptPublisher;
import com.example.relay.deliveryengine.worker.ExecutionOwnershipMetrics;

@Component
public class ReconciliationSweeper {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationSweeper.class);

    private AttemptRepository attemptRepository;
    private AttemptExecutionRepository executionRepository;
    private AttemptPublisher attemptPublisher;
    private AttemptService attemptService;
    private ReconciliationProperties reconciliationProperties;
    private ExecutionOwnershipMetrics executionOwnershipMetrics;
    private ScheduledJobMetrics scheduledJobMetrics;

    public ReconciliationSweeper(
        AttemptRepository attemptRepository,
        AttemptExecutionRepository executionRepository,
        AttemptPublisher attemptPublisher,
        AttemptService attemptService,
        ReconciliationProperties reconciliationProperties,
        ExecutionOwnershipMetrics executionOwnershipMetrics
    ) {
        this(attemptRepository, executionRepository, attemptPublisher, attemptService, reconciliationProperties,
                executionOwnershipMetrics, null);
    }

    @Autowired
    public ReconciliationSweeper(
        AttemptRepository attemptRepository,
        AttemptExecutionRepository executionRepository,
        AttemptPublisher attemptPublisher,
        AttemptService attemptService,
        ReconciliationProperties reconciliationProperties,
        ExecutionOwnershipMetrics executionOwnershipMetrics,
        ScheduledJobMetrics scheduledJobMetrics
    ) {
        this.attemptRepository = attemptRepository;
        this.executionRepository = executionRepository;
        this.attemptPublisher = attemptPublisher;
        this.attemptService = attemptService;
        this.reconciliationProperties = reconciliationProperties;
        this.executionOwnershipMetrics = executionOwnershipMetrics;
        this.scheduledJobMetrics = scheduledJobMetrics;
    }

    @Scheduled(fixedDelayString = "${relay.reconciliation.interval}", scheduler = SchedulerNames.DELIVERY_RECONCILIATION)
    public void scheduledSweep() {
        Runnable callback = () -> {
            if (reconciliationProperties.isSchedulingEnabled()) {
                sweep();
            }
        };
        if (scheduledJobMetrics == null) callback.run();
        else scheduledJobMetrics.run("delivery-reconciliation", reconciliationProperties.getInterval(), callback);
    }

    public void sweep() {
        recoverInFlight();
        recoverDeadLetter();
    }

    private void recoverInFlight() {
        Duration grace = reconciliationProperties.getInFlightGrace();
        List<AttemptExecutionCandidate> attempts = executionRepository.findStaleInFlight(
                grace, reconciliationProperties.getBatchSize());

        for (AttemptExecutionCandidate attempt : attempts) {
            long ageMillis = Duration.between(attempt.claimedAt(), Instant.now()).toMillis();
            if (attemptService.resetStuck(attempt.id(), attempt.generation(), grace) == 1) {
                executionOwnershipMetrics.recordRevoked("revoked");
                log.warn("Revoked stale IN_FLIGHT attempt {} generation {} after {} ms",
                        attempt.id(), attempt.generation(), ageMillis);
            } else {
                executionOwnershipMetrics.recordRevoked("lost_race");
                log.info("Stale IN_FLIGHT attempt {} generation {} lost a reconciliation race",
                        attempt.id(), attempt.generation());
            }
        }
    }

    // touchDeadLetterCandidate() only advances updated_at — it never sets dead_letter_notified_at.
    // Setting that column is DeadLetterNotifier's job (delivery.deadletter package), done via its
    // own atomic claim when it actually processes the message. This asymmetry (sweep bumps
    // updated_at, consumer sets dead_letter_notified_at) is what lets the two guards compose
    // without racing each other.
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
