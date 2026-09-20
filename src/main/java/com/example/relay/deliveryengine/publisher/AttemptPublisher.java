package com.example.relay.deliveryengine.publisher;

import com.example.relay.deliveryengine.config.RabbitMqConfig;
import com.example.relay.deliveryengine.retry.RetryProperties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class AttemptPublisher implements ReadyTaskPublisher {
    private static final Logger log = LoggerFactory.getLogger(AttemptPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final RetryProperties retryProperties;

    public AttemptPublisher(RabbitTemplate rabbitTemplate, RetryProperties retryProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.retryProperties = retryProperties;
    }

    public void publish(UUID attemptId) {
        publishReady(attemptId).whenComplete((outcome, error) -> {
            if (error != null || outcome != ReadyPublishOutcome.CONFIRMED) {
                log.warn("Ready task publication did not confirm for attempt {}: outcome={}, error={}", attemptId,
                        outcome, error == null ? "none" : error.toString());
            }
        });
    }

    @Override
    public CompletableFuture<ReadyPublishOutcome> publishReady(UUID attemptId) {
        CorrelationData correlationData = new CorrelationData(attemptId.toString());

        try {
            rabbitTemplate.convertAndSend(RabbitMqConfig.DELIVERY_EXCHANGE, RabbitMqConfig.TASKS_ROUTING_KEY,
                    attemptId.toString(), message -> {
                        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        return message;
                    }, correlationData);
        } catch (AmqpException exception) {
            log.warn("Failed to publish ready attempt {}", attemptId, exception);
            return CompletableFuture.completedFuture(ReadyPublishOutcome.DEFINITE_FAILURE);
        }

        return correlationData.getFuture()
                .orTimeout(retryProperties.getPublishConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .handle((confirm, error) -> {
                    if (error != null) {
                        return ReadyPublishOutcome.AMBIGUOUS;
                    }
                    if (!confirm.isAck() || correlationData.getReturned() != null) {
                        return ReadyPublishOutcome.DEFINITE_FAILURE;
                    }
                    return ReadyPublishOutcome.CONFIRMED;
                });
    }

    public void publishToRoutingKey(UUID attemptId, String routingKey) {
        CorrelationData correlationData = new CorrelationData(attemptId.toString());

        try {
            // convertAndSend's return value does not denote that the message was accepted by rabbitmq, or
            // it was routed correctly
            // RabbitMQ processes it asynchronously. Hence, we require the callbacks for confirm and return
            rabbitTemplate.convertAndSend(RabbitMqConfig.DELIVERY_EXCHANGE, routingKey,
                    attemptId.toString(), correlationData);
        } catch (Exception ex) {
            log.warn("Failed to publish attempt {} to routing key {}", attemptId, routingKey, ex);
        }
    }
}
