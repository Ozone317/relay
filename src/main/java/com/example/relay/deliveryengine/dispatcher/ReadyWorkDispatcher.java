package com.example.relay.deliveryengine.dispatcher;

import com.example.relay.attempt.infrastructure.ReadyWorkRepository;
import com.example.relay.common.scheduling.SchedulerNames;
import com.example.relay.deliveryengine.publisher.ReadyPublishOutcome;
import com.example.relay.deliveryengine.publisher.ReadyTaskPublisher;
import com.example.relay.deliveryengine.retry.RetryProperties;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReadyWorkDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ReadyWorkDispatcher.class);

    private final ReadyWorkRepository readyWorkRepository;
    private final ReadyTaskPublisher readyTaskPublisher;
    private final Executor confirmationExecutor;
    private final RetryProperties retryProperties;

    public ReadyWorkDispatcher(ReadyWorkRepository readyWorkRepository, ReadyTaskPublisher readyTaskPublisher,
            @Qualifier("readyWorkConfirmationExecutor") Executor confirmationExecutor,
            RetryProperties retryProperties) {
        this.readyWorkRepository = readyWorkRepository;
        this.readyTaskPublisher = readyTaskPublisher;
        this.confirmationExecutor = confirmationExecutor;
        this.retryProperties = retryProperties;
    }

    @Scheduled(fixedDelayString = "${relay.retry.dispatcher-interval}", scheduler = SchedulerNames.DELIVERY_PROGRESS)
    public void scheduledDispatch() {
        if (!retryProperties.isSchedulingEnabled()) {
            return;
        }
        dispatchOnce();
    }

    public void dispatchOnce() {
        UUID claimId = UUID.randomUUID();
        List<UUID> attemptIds;
        try {
            attemptIds = readyWorkRepository.claimUnpublishedReady(claimId,
                    retryProperties.getUnconfirmedReadyGrace(), retryProperties.getDispatcherBatchSize());
        } catch (RuntimeException exception) {
            log.warn("Unable to claim ready work for publication", exception);
            return;
        }

        for (UUID attemptId : attemptIds) {
            try {
                CompletableFuture<ReadyPublishOutcome> publication = readyTaskPublisher.publishReady(attemptId);
                publication.thenAcceptAsync(outcome -> handleOutcome(attemptId, claimId, outcome), confirmationExecutor)
                        .whenComplete((ignored, error) -> {
                            if (error != null) {
                                log.warn("Ready publication completion failed for attempt {}", attemptId, error);
                            }
                        });
            } catch (RuntimeException exception) {
                log.warn("Unable to start ready publication confirmation handling for attempt {}", attemptId,
                        exception);
            }
        }
    }

    private void handleOutcome(UUID attemptId, UUID claimId, ReadyPublishOutcome outcome) {
        if (outcome == ReadyPublishOutcome.CONFIRMED) {
            int marked = readyWorkRepository.markReadyPublished(attemptId, claimId);
            if (marked == 0) {
                log.info("Ready publication confirmation lost the claim for attempt {}", attemptId);
            }
        } else {
            log.warn("Ready publication did not confirm for attempt {}: {}", attemptId, outcome);
        }
    }
}
