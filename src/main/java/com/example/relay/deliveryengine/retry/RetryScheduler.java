package com.example.relay.deliveryengine.retry;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.relay.common.scheduling.SchedulerNames;
import com.example.relay.common.scheduling.ScheduledCallbackRunner;
import com.example.relay.common.scheduling.ScheduledJob;
import com.example.relay.attempt.infrastructure.ReadyWorkRepository;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    private final ReadyWorkRepository readyWorkRepository;
    private final RetryProperties retryProperties;
    private final ScheduledCallbackRunner scheduledCallbackRunner;

    @Autowired
    public RetryScheduler(ReadyWorkRepository readyWorkRepository, RetryProperties retryProperties,
            ScheduledCallbackRunner scheduledCallbackRunner) {
        this.readyWorkRepository = readyWorkRepository;
        this.retryProperties = retryProperties;
        this.scheduledCallbackRunner = scheduledCallbackRunner;
    }

    @Scheduled(fixedDelayString = "${relay.retry.scheduler-interval}", scheduler = SchedulerNames.DELIVERY_PROGRESS)
    public void scheduledReleaseDueRetries() {
        scheduledCallbackRunner.run(ScheduledJob.RETRY_PROMOTION, retryProperties.getSchedulerInterval(), () -> {
            if (retryProperties.isSchedulingEnabled()) {
                releaseDueRetries();
            }
        });
    }

    public void releaseDueRetries() {
        List<UUID> promoted = readyWorkRepository.promoteDueScheduled(retryProperties.getSchedulerBatchSize());
        if (!promoted.isEmpty()) {
            log.info("Promoted {} due SCHEDULED attempts to durable CREATED ready work", promoted.size());
        }
    }
}
