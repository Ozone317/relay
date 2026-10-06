package com.example.relay.deliveryengine.retry;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.relay.common.scheduling.SchedulerNames;
import com.example.relay.attempt.infrastructure.ReadyWorkRepository;

@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    private final ReadyWorkRepository readyWorkRepository;
    private final RetryProperties retryProperties;

    public RetryScheduler(ReadyWorkRepository readyWorkRepository, RetryProperties retryProperties) {
        this.readyWorkRepository = readyWorkRepository;
        this.retryProperties = retryProperties;
    }

    @Scheduled(fixedDelayString = "${relay.retry.scheduler-interval}", scheduler = SchedulerNames.DELIVERY_PROGRESS)
    public void scheduledReleaseDueRetries() {
        if (!retryProperties.isSchedulingEnabled()) {
            return;
        }
        releaseDueRetries();
    }

    public void releaseDueRetries() {
        List<UUID> promoted = readyWorkRepository.promoteDueScheduled(retryProperties.getSchedulerBatchSize());
        if (!promoted.isEmpty()) {
            log.info("Promoted {} due SCHEDULED attempts to durable CREATED ready work", promoted.size());
        }
    }
}
