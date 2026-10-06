package com.example.relay.deliveryengine.retry;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.relay.common.scheduling.SchedulerNames;
import com.example.relay.common.scheduling.ScheduledJobMetrics;
import com.example.relay.attempt.infrastructure.ReadyWorkRepository;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    private final ReadyWorkRepository readyWorkRepository;
    private final RetryProperties retryProperties;
    private final ScheduledJobMetrics scheduledJobMetrics;

    public RetryScheduler(ReadyWorkRepository readyWorkRepository, RetryProperties retryProperties) {
        this(readyWorkRepository, retryProperties, null);
    }

    @Autowired
    public RetryScheduler(ReadyWorkRepository readyWorkRepository, RetryProperties retryProperties,
            ScheduledJobMetrics scheduledJobMetrics) {
        this.readyWorkRepository = readyWorkRepository;
        this.retryProperties = retryProperties;
        this.scheduledJobMetrics = scheduledJobMetrics;
    }

    @Scheduled(fixedDelayString = "${relay.retry.scheduler-interval}", scheduler = SchedulerNames.DELIVERY_PROGRESS)
    public void scheduledReleaseDueRetries() {
        runScheduled("retry-promotion", retryProperties.getSchedulerInterval(), () -> {
            if (retryProperties.isSchedulingEnabled()) {
                releaseDueRetries();
            }
        });
    }

    private void runScheduled(String job, Duration fixedDelay, Runnable callback) {
        if (scheduledJobMetrics == null) callback.run();
        else scheduledJobMetrics.run(job, fixedDelay, callback);
    }

    public void releaseDueRetries() {
        List<UUID> promoted = readyWorkRepository.promoteDueScheduled(retryProperties.getSchedulerBatchSize());
        if (!promoted.isEmpty()) {
            log.info("Promoted {} due SCHEDULED attempts to durable CREATED ready work", promoted.size());
        }
    }
}
