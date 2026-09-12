package com.example.relay.email;

import com.example.relay.email.config.EmailQueueConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Deliberately NOT reusing deliveryengine's raw-string message convention (AttemptPublisher publishes a bare UUID
 * string via the default SimpleMessageConverter) - this message needs structure, and changing the global RabbitTemplate
 * message converter to support that would risk affecting every existing delivery-engine queue's String-based contract.
 * JSON (de)serialization here is local to this package, via the already-autoconfigured Spring Boot ObjectMapper - no
 * new dependency.
 */
@Component
public class EmailDispatchPublisher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatchPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public EmailDispatchPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(EmailDispatchMessage message) {
        try {
            String payload = objectMapper.writeValueAsString(message);
            rabbitTemplate.convertAndSend(EmailQueueConfig.EMAIL_EXCHANGE, EmailQueueConfig.DISPATCH_ROUTING_KEY,
                    payload);
        } catch (Exception ex) {
            log.warn("Failed to publish email dispatch message (template {}, idempotency key {})", message.template(),
                    message.idempotencyKey(), ex);
        }
    }
}
