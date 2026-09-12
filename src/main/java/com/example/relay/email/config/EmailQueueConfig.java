package com.example.relay.email.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Deliberately its own exchange, not deliveryengine.config.RabbitMqConfig - an unrelated concern, and this project's
 * own precedent (BrevoEmailClientConfig vs DeliveryHttpClientConfig) is not to share infrastructure across concerns
 * just because it is convenient.
 */
@Configuration
public class EmailQueueConfig {

    public static final String EMAIL_EXCHANGE = "relay.email";
    public static final String DISPATCH_QUEUE = "email.dispatch";
    public static final String DISPATCH_ROUTING_KEY = "dispatch";

    @Bean
    public DirectExchange emailExchange() {
        return new DirectExchange(EMAIL_EXCHANGE);
    }

    @Bean
    public Queue emailDispatchQueue() {
        return QueueBuilder.durable(DISPATCH_QUEUE).build();
    }

    @Bean
    public Binding emailDispatchBinding(Queue emailDispatchQueue, DirectExchange emailExchange) {
        return BindingBuilder.bind(emailDispatchQueue).to(emailExchange).with(DISPATCH_ROUTING_KEY);
    }
}
