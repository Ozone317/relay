package com.example.relay.deliveryengine.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
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

    @Bean
    public DirectExchange deliveryExchange() {
        return new DirectExchange(DELIVERY_EXCHANGE);
    }

    @Bean
    public Queue tasksQueue() {
        return QueueBuilder.durable(TASKS_QUEUE).build();
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

    @Bean
    public SimpleRabbitListenerContainerFactory deliveryListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            DeliveryListenerProperties deliveryListenerProperties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setConcurrentConsumers(deliveryListenerProperties.getConsumerConcurrency());
        factory.setMaxConcurrentConsumers(deliveryListenerProperties.getConsumerConcurrency());
        factory.setPrefetchCount(deliveryListenerProperties.getPrefetchCount());
        return factory;
    }
}
