package com.example.relay.deliveryengine.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.relay.deliveryengine.config.RabbitMqConfig;
import com.example.relay.support.SharedPostgresContainer;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Binding.DestinationType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
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
@Tag("integration")
@SpringBootTest
@TestPropertySource(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Testcontainers
@TestMethodOrder(OrderAnnotation.class)
class AttemptPublisherIntegrationTest implements SharedPostgresContainer {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4.3.6-management");

    @Autowired
    private AttemptPublisher underTest;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Test
    @Order(1)
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
    @Order(2)
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

    @Test
    @Order(3)
    void taskPublish_returnsConfirmed_andMessageIsPersistent() throws Exception {
        UUID attemptId = UUID.randomUUID();

        ReadyPublishOutcome outcome = underTest.publishReady(attemptId).get(10, TimeUnit.SECONDS);

        assertEquals(ReadyPublishOutcome.CONFIRMED, outcome);
        Message received = rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 5000);
        assertNotNull(received);
        assertEquals(attemptId.toString(), new String(received.getBody()));
        assertEquals(MessageDeliveryMode.PERSISTENT, received.getMessageProperties().getReceivedDeliveryMode());
    }

    @Test
    @Order(4)
    void taskPublish_returnsDefiniteFailure_whenMandatoryReturnOccurs() throws Exception {
        Binding tasksBinding = new Binding(
                RabbitMqConfig.TASKS_QUEUE,
                DestinationType.QUEUE,
                RabbitMqConfig.DELIVERY_EXCHANGE,
                RabbitMqConfig.TASKS_ROUTING_KEY,
                null);
        rabbitAdmin.removeBinding(tasksBinding);

        try {
            ReadyPublishOutcome outcome = underTest.publishReady(UUID.randomUUID()).get(10, TimeUnit.SECONDS);

            assertEquals(ReadyPublishOutcome.DEFINITE_FAILURE, outcome);
        } finally {
            rabbitAdmin.declareBinding(tasksBinding);
        }
    }

    @Test
    @Order(5)
    void taskQueue_isPresentAndDurable() {
        Properties queueProperties = rabbitAdmin.getQueueProperties(RabbitMqConfig.TASKS_QUEUE);

        assertThat(queueProperties).isNotNull();
        assertThat(queueProperties.getProperty("QUEUE_NAME")).isEqualTo(RabbitMqConfig.TASKS_QUEUE);
    }

    @Test
    @Order(6)
    void confirmedQueuedTask_survivesBrokerRestart() throws Exception {
        UUID attemptId = UUID.randomUUID();
        assertEquals(ReadyPublishOutcome.CONFIRMED,
                underTest.publishReady(attemptId).get(10, TimeUnit.SECONDS));

        rabbitMQContainer.execInContainer("rabbitmqctl", "stop_app");
        rabbitMQContainer.execInContainer("rabbitmqctl", "start_app");

        org.awaitility.Awaitility.await().ignoreExceptions().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            Message received = rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 500);
            assertNotNull(received);
            assertEquals(attemptId.toString(), new String(received.getBody()));
            assertEquals(MessageDeliveryMode.PERSISTENT, received.getMessageProperties().getReceivedDeliveryMode());
        });
    }
}
