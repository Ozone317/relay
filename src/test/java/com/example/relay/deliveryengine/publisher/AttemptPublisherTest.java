package com.example.relay.deliveryengine.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.example.relay.deliveryengine.retry.RetryProperties;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class AttemptPublisherTest {

    @Test
    void eachPhysicalPublishGetsAUniqueCorrelationId() throws Exception {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AtomicReference<CorrelationData> first = new AtomicReference<>();
        AtomicReference<CorrelationData> second = new AtomicReference<>();
        doAnswer(invocation -> {
            if (first.get() == null) {
                first.set(invocation.getArgument(4));
            } else {
                second.set(invocation.getArgument(4));
            }
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class),
                any(CorrelationData.class));

        AttemptPublisher publisher = new AttemptPublisher(rabbitTemplate, new RetryProperties());
        UUID attemptId = UUID.randomUUID();
        CompletableFuture<ReadyPublishOutcome> firstOutcome = publisher.publishReady(attemptId);
        CompletableFuture<ReadyPublishOutcome> secondOutcome = publisher.publishReady(attemptId);
        first.get().getFuture().complete(new CorrelationData.Confirm(true, null));
        second.get().getFuture().complete(new CorrelationData.Confirm(true, null));

        assertThat(first.get().getId()).isNotEqualTo(second.get().getId());
        assertThat(firstOutcome.get(1, TimeUnit.SECONDS)).isEqualTo(ReadyPublishOutcome.CONFIRMED);
        assertThat(secondOutcome.get(1, TimeUnit.SECONDS)).isEqualTo(ReadyPublishOutcome.CONFIRMED);
    }

    @Test
    void brokerNackWithoutReasonIsDefiniteFailure() throws Exception {
        AtomicReference<CorrelationData> captured = new AtomicReference<>();
        RabbitTemplate rabbitTemplate = stubPublisher(captured);
        AttemptPublisher publisher = new AttemptPublisher(rabbitTemplate, new RetryProperties());

        CompletableFuture<ReadyPublishOutcome> outcome = publisher.publishReady(UUID.randomUUID());
        captured.get().getFuture().complete(new CorrelationData.Confirm(false, null));

        assertThat(outcome.get(1, TimeUnit.SECONDS)).isEqualTo(ReadyPublishOutcome.DEFINITE_FAILURE);
    }

    @Test
    void connectionLossReasonMakesNackAmbiguous() throws Exception {
        AtomicReference<CorrelationData> captured = new AtomicReference<>();
        RabbitTemplate rabbitTemplate = stubPublisher(captured);
        AttemptPublisher publisher = new AttemptPublisher(rabbitTemplate, new RetryProperties());

        CompletableFuture<ReadyPublishOutcome> outcome = publisher.publishReady(UUID.randomUUID());
        captured.get().getFuture().complete(new CorrelationData.Confirm(false, "Channel shutdown: connection error"));

        assertThat(outcome.get(1, TimeUnit.SECONDS)).isEqualTo(ReadyPublishOutcome.AMBIGUOUS);
    }

    @Test
    void sendExceptionRemainsDefiniteFailure() throws Exception {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doThrow(new AmqpException("send failed")).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        AttemptPublisher publisher = new AttemptPublisher(rabbitTemplate, new RetryProperties());

        assertThat(publisher.publishReady(UUID.randomUUID()).get(1, TimeUnit.SECONDS))
                .isEqualTo(ReadyPublishOutcome.DEFINITE_FAILURE);
    }

    @Test
    void confirmationTimeoutRemainsAmbiguous() throws Exception {
        AtomicReference<CorrelationData> captured = new AtomicReference<>();
        RabbitTemplate rabbitTemplate = stubPublisher(captured);
        RetryProperties properties = new RetryProperties();
        properties.setPublishConfirmTimeout(Duration.ofMillis(1));
        AttemptPublisher publisher = new AttemptPublisher(rabbitTemplate, properties);

        assertThat(publisher.publishReady(UUID.randomUUID()).get(1, TimeUnit.SECONDS))
                .isEqualTo(ReadyPublishOutcome.AMBIGUOUS);
    }

    @Test
    void retainsOneArgumentConstructor() throws Exception {
        AtomicReference<CorrelationData> captured = new AtomicReference<>();
        RabbitTemplate rabbitTemplate = stubPublisher(captured);
        AttemptPublisher publisher = new AttemptPublisher(rabbitTemplate);

        CompletableFuture<ReadyPublishOutcome> outcome = publisher.publishReady(UUID.randomUUID());
        captured.get().getFuture().complete(new CorrelationData.Confirm(true, null));

        assertThat(outcome.get(1, TimeUnit.SECONDS)).isEqualTo(ReadyPublishOutcome.CONFIRMED);
    }

    private RabbitTemplate stubPublisher(AtomicReference<CorrelationData> captured) {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(4));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class),
                any(CorrelationData.class));
        return rabbitTemplate;
    }
}
