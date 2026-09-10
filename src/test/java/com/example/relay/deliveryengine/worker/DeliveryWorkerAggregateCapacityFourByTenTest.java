package com.example.relay.deliveryengine.worker;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class DeliveryWorkerAggregateCapacityFourByTenTest extends AbstractDeliveryWorkerAggregateCapacityTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4-management");

    @DynamicPropertySource
    static void overrideDeliveryListenerProperties(DynamicPropertyRegistry registry) {
        registry.add("relay.delivery.consumer-concurrency", () -> 4);
        registry.add("relay.delivery.prefetch-count", () -> 10);
    }

    @Override
    protected int consumerConcurrency() { return 4; }

    @Override
    protected int prefetchCount() { return 10; }
}
