package com.example.relay.delivery.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqConfig.class);

    public static final String DELIVERY_EXCHANGE = "relay.delivery";

    public static final String TASKS_QUEUE = "delivery.tasks";
    public static final String TASKS_ROUTING_KEY = "tasks";

    public static final String DEADLETTER_QUEUE = "delivery.deadletter";
    public static final String DEADLETTER_ROUTING_KEY = "deadletter";

    public static final String WAIT_30S_QUEUE = "delivery.wait.30s";
    public static final String WAIT_30S_ROUTING_KEY = "wait.30s";

    public static final String WAIT_2M_QUEUE = "delivery.wait.2m";
    public static final String WAIT_2M_ROUTING_KEY = "wait.2m";

    public static final String WAIT_10M_QUEUE = "delivery.wait.10m";
    public static final String WAIT_10M_ROUTING_KEY = "wait.10m";

    public static final String WAIT_1H_QUEUE = "delivery.wait.1h";
    public static final String WAIT_1H_ROUTING_KEY = "wait.1h";

    public static final String WAIT_6H_QUEUE = "delivery.wait.6h";
    public static final String WAIT_6H_ROUTING_KEY = "wait.6h";

    @Bean
    public DirectExchange deliveryExchange() {
        return new DirectExchange(DELIVERY_EXCHANGE);
    }

    @Bean
    public Queue tasksQueue() {
        return QueueBuilder.durable(TASKS_QUEUE).build();
    }

    @Bean
    public Queue wait30sQueue() {
        return QueueBuilder.durable(WAIT_30S_QUEUE).ttl(30_000).deadLetterExchange(DELIVERY_EXCHANGE)
                .deadLetterRoutingKey(TASKS_ROUTING_KEY).build();
    }

    @Bean
    public Queue wait2mQueue() {
        return QueueBuilder.durable(WAIT_2M_QUEUE).ttl(120_000).deadLetterExchange(DELIVERY_EXCHANGE)
                .deadLetterRoutingKey(TASKS_ROUTING_KEY).build();
    }

    @Bean
    public Queue wait10mQueue() {
        return QueueBuilder.durable(WAIT_10M_QUEUE).ttl(600_000).deadLetterExchange(DELIVERY_EXCHANGE)
                .deadLetterRoutingKey(TASKS_ROUTING_KEY).build();
    }

    @Bean
    public Queue wait1hQueue() {
        return QueueBuilder.durable(WAIT_1H_QUEUE).ttl(3_600_000).deadLetterExchange(DELIVERY_EXCHANGE)
                .deadLetterRoutingKey(TASKS_ROUTING_KEY).build();
    }

    @Bean
    public Queue wait6hQueue() {
        return QueueBuilder.durable(WAIT_6H_QUEUE).ttl(21_600_000).deadLetterExchange(DELIVERY_EXCHANGE)
                .deadLetterRoutingKey(TASKS_ROUTING_KEY).build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEADLETTER_QUEUE).build();
    }

    @Bean
    public Binding tasksBinding(Queue tasksQueue, DirectExchange deliveryExchange) {
        return BindingBuilder.bind(tasksQueue).to(deliveryExchange).with(TASKS_ROUTING_KEY);
    }

    @Bean
    public Binding wait30sQueueBinding(Queue wait30sQueue, DirectExchange deliveryExchange) {
        return BindingBuilder.bind(wait30sQueue).to(deliveryExchange).with(WAIT_30S_ROUTING_KEY);
    }

    @Bean
    public Binding wait2mQueueBinding(Queue wait2mQueue, DirectExchange deliveryExchange) {
        return BindingBuilder.bind(wait2mQueue).to(deliveryExchange).with(WAIT_2M_ROUTING_KEY);
    }

    @Bean
    public Binding wait10mQueueBinding(Queue wait10mQueue, DirectExchange deliveryExchange) {
        return BindingBuilder.bind(wait10mQueue).to(deliveryExchange).with(WAIT_10M_ROUTING_KEY);
    }

    @Bean
    public Binding wait1hQueueBinding(Queue wait1hQueue, DirectExchange deliveryExchange) {
        return BindingBuilder.bind(wait1hQueue).to(deliveryExchange).with(WAIT_1H_ROUTING_KEY);
    }

    @Bean
    public Binding wait6hQueueBinding(Queue wait6hQueue, DirectExchange deliveryExchange) {
        return BindingBuilder.bind(wait6hQueue).to(deliveryExchange).with(WAIT_6H_ROUTING_KEY);
    }

    @Bean
    public Binding deadLetterQueueBinding(Queue deadLetterQueue, DirectExchange deliveryExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deliveryExchange).with(DEADLETTER_ROUTING_KEY);
    }

    @Bean
    public RabbitTemplateCustomizer rabbitTemplateCustomizer() {
        return rabbitTemplate -> {
            rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
                if (!ack) {
                    log.warn("RabbitMQ rejected publish for {}: {}", correlationData.getId(), cause);
                }
            });

            rabbitTemplate.setReturnsCallback(returnedMessage -> {
                log.warn(
                        "RabbitMQ returned unroutable message: exchange={}, routingKey={}, "
                                + "replyCode={}, replyText={}",
                        returnedMessage.getExchange(), returnedMessage.getRoutingKey(), returnedMessage.getReplyCode(),
                        returnedMessage.getReplyText());
            });
        };
    }
}
