package com.example.relay.delivery.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class RabbitMqConfigIntegrationTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4-management");

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private Logger rabbitMqConfigLogger;

    @BeforeEach
    void setUp() {
        rabbitMqConfigLogger = (Logger) LoggerFactory.getLogger(RabbitMqConfig.class);
    }

    @Test
    void unroutablePublish_triggersReturnsCallback_andLogsWarning() throws InterruptedException {
        // Arrange - a routing key nothing is bound to. The broker accepts the publish (the
        // exchange exists) but can't route it anywhere, so it comes back via the ReturnsCallback
        // that RabbitMqConfig already wires onto this exact RabbitTemplate bean. We can't attach
        // our own ReturnsCallback to observe this directly - RabbitTemplate only supports one,
        // and it's already taken by the production callback - so instead we capture what that
        // callback actually does: log a warning.
        CountDownLatch warningLogged = new CountDownLatch(1);
        LatchingAppender appender = new LatchingAppender(warningLogged, "RabbitMQ returned unroutable message");
        appender.start();
        rabbitMqConfigLogger.addAppender(appender);

        try {
            // Act
            rabbitTemplate.convertAndSend(RabbitMqConfig.DELIVERY_EXCHANGE, "no.such.binding", "probe",
                    new CorrelationData(UUID.randomUUID().toString()));

            // Assert
            assertTrue(warningLogged.await(5, TimeUnit.SECONDS),
                    "expected RabbitMqConfig's ReturnsCallback to log a warning within 5s");
        } finally {
            rabbitMqConfigLogger.detachAppender(appender);
        }
    }

    /**
     * Counts down a latch the moment a log event containing the expected text is appended - lets a test await an async
     * log side effect instead of polling or sleeping.
     */
    private static class LatchingAppender extends AppenderBase<ILoggingEvent> {

        private final CountDownLatch latch;
        private final String expectedSubstring;

        LatchingAppender(CountDownLatch latch, String expectedSubstring) {
            this.latch = latch;
            this.expectedSubstring = expectedSubstring;
        }

        @Override
        protected void append(ILoggingEvent event) {
            if (event.getFormattedMessage().contains(expectedSubstring)) {
                latch.countDown();
            }
        }
    }
}
