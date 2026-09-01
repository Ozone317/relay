package com.example.relay.delivery.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.relay.delivery.config.RabbitMqConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// DeliveryWorker's @RabbitListener on TASKS_QUEUE would otherwise race this test's own
// rabbitTemplate.receive(TASKS_QUEUE, ...) for the same message - this test is about publishing,
// not consumption, so the real listener is disabled for this context.
@SpringBootTest
@TestPropertySource(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Testcontainers
class AttemptPublisherIntegrationTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4-management");

    @Autowired
    private AttemptPublisher underTest;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    void publish_deliversAttemptIdToTasksQueue() {
        // Arrange
        UUID attemptId = UUID.randomUUID();

        // Act
        underTest.publish(attemptId);

        // Assert - read the real queue directly instead of hooking the confirm callback: the
        // RabbitTemplate bean already has its one allowed ConfirmCallback wired by RabbitMqConfig
        // for production logging, so a second one can't be attached here (RabbitTemplate only
        // supports a single callback - see RabbitMqConfigIntegrationTest for that behavior).
        Message received = rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 5000);

        assertNotNull(received, "expected a message on " + RabbitMqConfig.TASKS_QUEUE);
        assertEquals(attemptId.toString(), new String(received.getBody()));
    }

    @Test
    void publishToRoutingKey_deliversAttemptIdToTheGivenQueue() {
        // Arrange
        UUID attemptId = UUID.randomUUID();

        // Act
        underTest.publishToRoutingKey(attemptId, RabbitMqConfig.WAIT_30S_ROUTING_KEY);

        // Assert
        Message received = rabbitTemplate.receive(RabbitMqConfig.WAIT_30S_QUEUE, 5000);
        assertNotNull(received, "expected a message on " + RabbitMqConfig.WAIT_30S_QUEUE);
        assertEquals(attemptId.toString(), new String(received.getBody()));
    }
}
