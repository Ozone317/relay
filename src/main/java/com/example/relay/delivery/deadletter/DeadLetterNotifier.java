package com.example.relay.delivery.deadletter;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.config.RabbitMqConfig;

@Component
public class DeadLetterNotifier {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterNotifier.class);

    private final AttemptRepository attemptRepository;
    private final AttemptService attemptService;

    public DeadLetterNotifier(AttemptRepository attemptRepository, AttemptService attemptService) {
        this.attemptRepository = attemptRepository;
        this.attemptService = attemptService;
    }

    @RabbitListener(id = "deadLetterNotifier", queues = RabbitMqConfig.DEADLETTER_QUEUE)
    public void onMessage(String attemptIdRaw) {
        UUID attemptId = UUID.fromString(attemptIdRaw);

        if (!attemptService.claimDeadLetterNotification(attemptId, Instant.now())) {
            log.info("Attempt {} was already notified, skipping", attemptId);
            return;
        }

        Attempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalStateException("Dead-lettered attempt " + attemptId + " not found"));

        log.warn("[STUB EMAIL] Delivery permanently failed for attempt {} (endpoint {}): "
                + "exhausted all retries, manual handling required", attempt.getId(), attempt.getEndpoint().getId());
    }
}
