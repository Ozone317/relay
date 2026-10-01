package com.example.relay.deliveryengine.worker;

import com.example.relay.attempt.application.AttemptExecution;
import com.example.relay.attempt.application.AttemptMutationOutcome;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.deliveryengine.config.RabbitMqConfig;
import com.example.relay.deliveryengine.http.WebhookDeliveryException;
import com.example.relay.deliveryengine.http.WebhookHeaders;
import com.example.relay.deliveryengine.http.WebhookHttpResponse;
import com.example.relay.deliveryengine.http.WebhookHttpTransport;
import com.example.relay.deliveryengine.publisher.AttemptPublisher;
import com.example.relay.deliveryengine.retry.RetryDelayCalculator;
import com.example.relay.deliveryengine.retry.RetryJitterSource;
import com.example.relay.deliveryengine.retry.RetryProperties;
import com.example.relay.deliveryengine.signing.HmacSigner;
import com.example.relay.endpoint.domain.Endpoint;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class DeliveryWorker {

    private final AttemptPublisher attemptPublisher;

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final AttemptService attemptService;
    private final HmacSigner hmacSigner;
    private final WebhookHttpTransport webhookHttpTransport;
    private final ExecutorService virtualThreadExecutor;
    private final RetryDelayCalculator retryDelayCalculator;

    public DeliveryWorker(AttemptService attemptService, HmacSigner hmacSigner,
            WebhookHttpTransport webhookHttpTransport, AttemptPublisher attemptPublisher,
            ExecutorService virtualThreadExecutor, Clock clock, RetryProperties retryProperties,
            RetryJitterSource retryJitterSource) {
        this.attemptService = attemptService;
        this.hmacSigner = hmacSigner;
        this.webhookHttpTransport = webhookHttpTransport;
        this.attemptPublisher = attemptPublisher;
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.retryDelayCalculator = new RetryDelayCalculator(clock, retryProperties, retryJitterSource);
    }

    @RabbitListener(id = "deliveryWorker", queues = RabbitMqConfig.TASKS_QUEUE,
            containerFactory = "deliveryListenerContainerFactory")
    public CompletableFuture<Void> onMessage(String attemptIdRaw) {
        return CompletableFuture.runAsync(() -> processMessage(attemptIdRaw), virtualThreadExecutor);
    }

    private void processMessage(String attemptIdRaw) {
        UUID attemptId = UUID.fromString(attemptIdRaw);

        AttemptExecution execution = attemptService.claim(attemptId).orElse(null);
        if (execution == null) {
            log.warn("Attempt {} was already claimed, skipping", attemptId);
            return;
        }
        deliver(execution);
    }

    private void deliver(AttemptExecution execution) {
        Attempt attempt = execution.attempt();
        Endpoint endpoint = attempt.getEndpoint();
        String relayId = attempt.getMessage().getId().toString();
        long timestamp = Instant.now().getEpochSecond();
        byte[] body = attempt.getMessage().getBody().toString().getBytes(StandardCharsets.UTF_8);
        String signature = hmacSigner.sign(relayId, timestamp, body, endpoint.getSigningSecret());

        long startedAt = System.currentTimeMillis();
        try {
            WebhookHttpResponse response = webhookHttpTransport.post(endpoint.getUrl(), body,
                    new WebhookHeaders(relayId, timestamp, signature));
            long latencyMs = System.currentTimeMillis() - startedAt;

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                attemptService.markSucceeded(execution, response.statusCode(), response.responseBody(), latencyMs);
            } else {
                handleFailure(execution, response.statusCode(), response.responseBody(), null, latencyMs);
            }
        } catch (WebhookDeliveryException ex) {
            long latencyMs = System.currentTimeMillis() - startedAt;
            handleFailure(execution, null, null, ex.failureCode() + ": " + ex.boundedDiagnostic(), latencyMs);
        }
    }

    private void handleFailure(AttemptExecution execution, Integer responseCode, String responseBody, String lastError,
            Long latencyMs) {
        Attempt attempt = execution.attempt();
        boolean isFinal = attempt.getAttemptNo() >= RetryTier.MAX_ATTEMPTS;
        AttemptStatus finalStatus = isFinal ? AttemptStatus.DEAD : AttemptStatus.FAILED_RETRYING;

        if (isFinal) {
            AttemptMutationOutcome outcome = attemptService.markFailed(execution, finalStatus, null, responseCode,
                    responseBody, lastError, latencyMs);
            if (outcome == AttemptMutationOutcome.APPLIED) {
                attemptPublisher.publishToRoutingKey(attempt.getId(), RabbitMqConfig.DEADLETTER_ROUTING_KEY);
            }
        } else {
            int nextAttemptNo = attempt.getAttemptNo() + 1;
            RetryTier tier = RetryTier.forAttemptNo(nextAttemptNo);
            Instant dueAt = retryDelayCalculator.nextRetryAt(tier.getDelay());

            attemptService.markFailedAndCreateRetry(execution, dueAt, responseCode, responseBody, lastError, latencyMs);
        }
    }

}
