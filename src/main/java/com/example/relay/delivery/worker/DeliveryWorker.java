package com.example.relay.delivery.worker;

import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.config.RabbitMqConfig;
import com.example.relay.delivery.publisher.AttemptPublisher;
import com.example.relay.delivery.signing.HmacSigner;
import com.example.relay.endpoint.domain.Endpoint;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class DeliveryWorker {

    private final AttemptPublisher attemptPublisher;

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final AttemptRepository attemptRepository;
    private final AttemptService attemptService;
    private final HmacSigner hmacSigner;
    private final RestClient deliveryRestClient;

    public DeliveryWorker(AttemptRepository attemptRepository, AttemptService attemptService, HmacSigner hmacSigner,
            RestClient deliveryRestClient, AttemptPublisher attemptPublisher) {
        this.attemptRepository = attemptRepository;
        this.attemptService = attemptService;
        this.hmacSigner = hmacSigner;
        this.deliveryRestClient = deliveryRestClient;
        this.attemptPublisher = attemptPublisher;
    }

    @RabbitListener(queues = RabbitMqConfig.TASKS_QUEUE)
    public void onMessage(String attemptIdRaw) {
        UUID attemptId = UUID.fromString(attemptIdRaw);

        if (!attemptService.claim(attemptId)) {
            log.warn("Attempt {} was already claimed, skipping", attemptId);
            return;
        }

        Attempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalStateException("Claimed attempt " + attemptId + " not found"));

        deliver(attempt);
    }

    private void deliver(Attempt attempt) {
        Endpoint endpoint = attempt.getEndpoint();
        String relayId = attempt.getMessage().getId().toString();
        long timestamp = Instant.now().getEpochSecond();
        String body = attempt.getMessage().getBody().toString();
        String signature = hmacSigner.sign(relayId, timestamp, body, endpoint.getSigningSecret());

        long startedAt = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = deliveryRestClient.post().uri(endpoint.getUrl()).header("relay-id", relayId)
                    .header("relay-timestamp", String.valueOf(timestamp)).header("relay-signature", signature).body(body)
                    .retrieve().onStatus(status -> true, (request, resp) -> {
                    }).toEntity(String.class);
            long latencyMs = System.currentTimeMillis() - startedAt;
            
            if (response.getStatusCode().is2xxSuccessful()) {
                attemptService.markSucceeded(attempt, response.getStatusCode().value(), response.getBody(), latencyMs);
            } else {
                handleFailure(attempt, response.getStatusCode().value(), response.getBody(), null, latencyMs);
            }
        } catch (RestClientException ex) {
            long latencyMs = System.currentTimeMillis() - startedAt;
            handleFailure(attempt, null, null, ex.getMessage(), latencyMs);
        }
    }

    private void handleFailure(Attempt attempt, Integer responseCode, String responseBody, String lastError, Long latencyMs) {
        boolean isFinal = attempt.getAttemptNo() >= RetryTier.MAX_ATTEMPTS;
        AttemptStatus finalStatus = isFinal ? AttemptStatus.DEAD : AttemptStatus.FAILED_RETRYING;
        
        if (isFinal) {
            attemptService.markFailed(attempt, finalStatus, null, responseCode, responseBody, lastError, latencyMs);
            attemptPublisher.publishToRoutingKey(attempt.getId(), RabbitMqConfig.DEADLETTER_ROUTING_KEY);
        } else {
            int nextAttemptNo = attempt.getAttemptNo() + 1;
            RetryTier tier = RetryTier.forAttemptNo(nextAttemptNo);
            Instant dueAt = Instant.now().plus(tier.getDelay());
            attemptService.markFailed(attempt, finalStatus, dueAt, responseCode, responseBody, lastError, latencyMs);

            Attempt retry = attemptService.createRetry(attempt, dueAt);
            attemptPublisher.publishToRoutingKey(retry.getId(), tier.getRoutingKey());
        }
    }
}
