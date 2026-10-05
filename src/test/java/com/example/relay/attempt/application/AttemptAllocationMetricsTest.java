package com.example.relay.attempt.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.relay.app.domain.App;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptAllocationRepository;
import com.example.relay.attempt.infrastructure.AttemptExecutionRepository;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.exception.DeliveryNotDeadException;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.message.domain.Message;
import com.example.relay.subscription.domain.Subscription;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.LoggerFactory;

class AttemptAllocationMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AttemptRepository attempts = mock(AttemptRepository.class);
    private final AttemptExecutionRepository executions = mock(AttemptExecutionRepository.class);
    private final DeliveryRepository deliveries = mock(DeliveryRepository.class);
    private final AttemptAllocationRepository allocation = mock(AttemptAllocationRepository.class);
    private AttemptService service;

    @BeforeEach
    void setUp() {
        service = new AttemptService(attempts, executions, deliveries, allocation,
                new AttemptAllocationMetrics(registry));
    }

    @Test
    void replayCreatedAndRejectedAreCountedOnceAtTheServiceDecision() {
        Delivery delivery = fixture();
        Attempt dead = new Attempt(delivery.getApp(), delivery.getMessage(), delivery.getEndpoint(), delivery, 6);
        dead.setStatus(AttemptStatus.DEAD);
        when(allocation.lockReplayAllocationParentsIfEndpointActive(delivery.getEndpoint().getId(), delivery.getId()))
                .thenReturn(true);
        when(attempts.findFirstByDeliveryIdOrderByAttemptNoDesc(delivery.getId()))
                .thenReturn(Optional.of(dead), Optional.of(new Attempt(delivery.getApp(), delivery.getMessage(),
                        delivery.getEndpoint(), delivery, 7)));
        when(allocation.nextAttemptNoUnderDeliveryLock(delivery.getId())).thenReturn(7);
        when(attempts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createReplay(delivery);
        assertThrows(DeliveryNotDeadException.class, () -> service.createReplay(delivery));

        assertCounter("replay", "created", 1);
        assertCounter("replay", "rejected", 1);
        assertOnlyBoundedTags();
    }

    @Test
    void retryOwnershipLossAndCreationHaveDistinctOutcomes() {
        Delivery delivery = fixture();
        Attempt parent = new Attempt(delivery.getApp(), delivery.getMessage(), delivery.getEndpoint(), delivery, 1);
        AttemptExecution execution = new AttemptExecution(parent, 1, Instant.now());
        when(executions.markFailed(any(), any(), any(), any(), any(), any(), any())).thenReturn(0, 1);
        when(allocation.nextAttemptNoUnderDeliveryLock(delivery.getId())).thenReturn(2);
        when(attempts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals(AttemptMutationOutcome.OWNERSHIP_LOST,
                service.markFailedAndCreateRetry(execution, Instant.now().plusSeconds(30), 503, null, null, 1L));
        assertEquals(AttemptMutationOutcome.APPLIED,
                service.markFailedAndCreateRetry(execution, Instant.now().plusSeconds(30), 503, null, null, 1L));

        assertCounter("retry", "ownership_lost", 1);
        assertCounter("retry", "created", 1);
        assertOnlyBoundedTags();
    }

    @Test
    void invariantFailureIsCountedAndOriginalExceptionPropagates() {
        Delivery delivery = fixture();
        Attempt dead = new Attempt(delivery.getApp(), delivery.getMessage(), delivery.getEndpoint(), delivery, 6);
        dead.setStatus(AttemptStatus.DEAD);
        when(allocation.lockReplayAllocationParentsIfEndpointActive(delivery.getEndpoint().getId(), delivery.getId()))
                .thenReturn(true);
        when(attempts.findFirstByDeliveryIdOrderByAttemptNoDesc(delivery.getId())).thenReturn(Optional.of(dead));
        when(allocation.nextAttemptNoUnderDeliveryLock(delivery.getId())).thenReturn(7);
        DataIntegrityViolationException failure = new DataIntegrityViolationException("unique violation");
        when(attempts.saveAndFlush(any())).thenThrow(failure);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.createReplay(delivery)));
        assertCounter("replay", "invariant_violation", 1);
        assertOnlyBoundedTags();
    }

    @Test
    void retryInvariantFailureLogsBoundedContextAndPreservesTheOriginalException() {
        Delivery delivery = fixture();
        Attempt parent = new Attempt(delivery.getApp(), delivery.getMessage(), delivery.getEndpoint(), delivery, 1);
        AttemptExecution execution = new AttemptExecution(parent, 1, Instant.now());
        when(executions.markFailed(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(allocation.nextAttemptNoUnderDeliveryLock(delivery.getId())).thenReturn(2);
        DataIntegrityViolationException failure = new DataIntegrityViolationException("sensitive-details",
                new SQLException("duplicate", "23505"));
        when(attempts.saveAndFlush(any())).thenThrow(failure);
        Logger logger = (Logger) LoggerFactory.getLogger(AttemptService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                    () -> service.markFailedAndCreateRetry(execution, Instant.now().plusSeconds(30),
                            503, null, null, 1L)));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertCounter("retry", "invariant_violation", 1);
        assertEquals(1, appender.list.size());
        String message = appender.list.get(0).getFormattedMessage();
        assertTrue(message.contains(delivery.getId().toString()));
        assertTrue(message.contains("creator=retry"));
        assertTrue(message.contains("attemptNo=2"));
        assertTrue(message.contains("sqlState=23505"));
        assertTrue(!message.contains("sensitive-details"));
        assertOnlyBoundedTags();
    }

    @Test
    void initialFanoutCountsEachCreatedAttempt() {
        Delivery delivery = fixture();
        Subscription subscription = new Subscription(delivery.getApp(),
                delivery.getMessage().getEvent(), delivery.getEndpoint());
        when(deliveries.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(attempts.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createFromSubscriptionList(List.of(subscription), delivery.getMessage());

        assertCounter("initial", "created", 1);
        assertOnlyBoundedTags();
    }

    private void assertCounter(String creator, String outcome, double expected) {
        assertEquals(expected, registry.get("relay.attempt.allocation")
                .tags("creator", creator, "outcome", outcome).counter().count());
    }

    private void assertOnlyBoundedTags() {
        for (Meter meter : registry.getMeters()) {
            assertEquals(2, meter.getId().getTags().size());
            assertEquals(List.of("creator", "outcome"),
                    meter.getId().getTags().stream().map(tag -> tag.getKey()).toList());
            assertTrue(List.of("initial", "retry", "replay")
                    .contains(meter.getId().getTag("creator")));
            assertTrue(List.of("created", "rejected", "ownership_lost", "invariant_violation")
                    .contains(meter.getId().getTag("outcome")));
        }
    }

    private Delivery fixture() {
        User user = new User("metrics@example.com", "hash");
        Environment environment = new Environment("test", "test", user);
        App app = new App("test", environment);
        Event event = new Event("payment.completed", app);
        Endpoint endpoint = new Endpoint("test", "https://example.com/hook", "secret", app);
        Message message = new Message(app, event, new ObjectMapper().createObjectNode());
        return new Delivery(app, message, endpoint);
    }
}
