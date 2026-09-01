package com.example.relay.delivery.publisher;

import com.example.relay.delivery.config.RabbitMqConfig;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class AttemptPublisher {
    private static final Logger log = LoggerFactory.getLogger(AttemptPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public AttemptPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(UUID attemptId) {
        publishToRoutingKey(attemptId, RabbitMqConfig.TASKS_ROUTING_KEY);
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
