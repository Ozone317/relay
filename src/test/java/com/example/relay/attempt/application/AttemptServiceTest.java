package com.example.relay.attempt.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.relay.app.domain.App;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptExecutionClaim;
import com.example.relay.attempt.infrastructure.AttemptExecutionRepository;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.message.domain.Message;
import com.example.relay.subscription.domain.Subscription;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class AttemptServiceTest {

    @Mock
    private AttemptRepository attemptRepository;

    @Mock
    private AttemptExecutionRepository executionRepository;

    @Mock
    private DeliveryRepository deliveryRepository;

    @InjectMocks
    private AttemptService underTest;

    @Test
    void createFromSubscriptionList_createsOneAttemptPerSubscription_withAttemptNoOne() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint1 = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Endpoint endpoint2 = new Endpoint("Staging", "https://staging.example.com/webhook", "whsec_2", app);
        Event event = new Event("payment.completed", app);
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        Message message = new Message(app, event, body);
        Subscription sub1 = new Subscription(app, event, endpoint1);
        Subscription sub2 = new Subscription(app, event, endpoint2);
        List<Subscription> subscriptions = List.of(sub1, sub2);

        // Stub
        when(attemptRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(deliveryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        List<Attempt> result = underTest.createFromSubscriptionList(subscriptions, message);

        // Assert
        assertEquals(2, result.size());
        for (Attempt attempt : result) {
            assertEquals(1, attempt.getAttemptNo());
            assertEquals(AttemptStatus.CREATED, attempt.getStatus());
            assertEquals(message.getId(), attempt.getMessage().getId());
            assertEquals(app.getId(), attempt.getApp().getId());
            assertEquals(message.getId(), attempt.getDelivery().getMessage().getId());
        }
        assertEquals(endpoint1.getId(), result.get(0).getEndpoint().getId());
        assertEquals(endpoint2.getId(), result.get(1).getEndpoint().getId());
    }

    @Test
    void createFromSubscriptionList_returnsEmptyList_whenSubscriptionsListIsEmpty() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        Message message = new Message(app, event, body);

        // Stub
        when(attemptRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        List<Attempt> result = underTest.createFromSubscriptionList(List.of(), message);

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void claim_returnsExecutionCapabilityWhenTheAttemptIsClaimed() {
        UUID attemptId = UUID.randomUUID();
        Instant claimedAt = Instant.now();
        Attempt attempt = new Attempt(null, null, null, null, 1);
        when(executionRepository.claim(attemptId)).thenReturn(Optional.of(new AttemptExecutionClaim(4L, claimedAt)));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));

        AttemptExecution result = underTest.claim(attemptId).orElseThrow();

        assertEquals(attempt, result.attempt());
        assertEquals(4L, result.generation());
        assertEquals(claimedAt, result.claimedAt());
    }

    @Test
    void claim_doesNotLoadAttemptWhenOwnershipIsLost() {
        UUID attemptId = UUID.randomUUID();
        when(executionRepository.claim(attemptId)).thenReturn(Optional.empty());

        assertTrue(underTest.claim(attemptId).isEmpty());
        verify(attemptRepository, never()).findById(attemptId);
    }

    @Test
    void createRetry_createsSavesAndReturnsScheduledAttemptWithIncreasedAttemptCountAndDueTime() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        Message message = new Message(app, event, body);
        Endpoint endpoint = new Endpoint("staging", "https://webhook.com", "whsec_some_secret", app);
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 1);
        Instant nextRetryAt = Instant.now().plusSeconds(30);

        // Stub
        when(attemptRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Attempt result = underTest.createRetry(attempt, nextRetryAt);

        // Assert
        assertEquals(attempt.getAttemptNo() + 1, result.getAttemptNo());
        assertEquals(AttemptStatus.SCHEDULED, result.getStatus());
        assertEquals(nextRetryAt, result.getNextRetryAt());
    }

    @Test
    void markSucceededTruncatesDiagnosticsAndUsesFencedRepositoryWithoutSavingDetachedAttempt() {
        Attempt attempt = new Attempt(null, null, null, null, 1);
        AttemptExecution execution = new AttemptExecution(attempt, 9L, Instant.now());
        String responseBody = "x".repeat(10_239) + "\uD83D\uDE00";
        when(executionRepository.markSucceeded(any(), eq(200), any(), eq(10L)))
                .thenReturn(AttemptMutationOutcome.APPLIED);

        AttemptMutationOutcome result = underTest.markSucceeded(execution, 200, responseBody, 10L);

        assertEquals(AttemptMutationOutcome.APPLIED, result);
        verify(executionRepository).markSucceeded(execution, 200, "x".repeat(10_239), 10L);
        verify(attemptRepository, never()).save(any());
        assertEquals(AttemptStatus.CREATED, attempt.getStatus());
    }

    @Test
    void markFailedTruncatesResponseAndErrorBeforeFencedRepositoryCall() {
        AttemptExecution execution = new AttemptExecution(new Attempt(null, null, null, null, 1), 2L, Instant.now());
        Instant nextRetryAt = Instant.now().plusSeconds(30);
        when(executionRepository.markFailed(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(AttemptMutationOutcome.OWNERSHIP_LOST);

        AttemptMutationOutcome result = underTest.markFailed(execution, AttemptStatus.FAILED_RETRYING, nextRetryAt, 503,
                "r".repeat(20_000), "e".repeat(20_000), 25L);

        assertEquals(AttemptMutationOutcome.OWNERSHIP_LOST, result);
        verify(executionRepository).markFailed(execution, AttemptStatus.FAILED_RETRYING, nextRetryAt, 503,
                "r".repeat(10_240), "e".repeat(10_240), 25L);
        verify(attemptRepository, never()).save(any());
    }

    @Test
    void markFailedAndCreateRetryDoesNotCreateRetryWhenExecutionOwnershipIsLost() {
        Attempt attempt = new Attempt(null, null, null, null, 1);
        AttemptExecution execution = new AttemptExecution(attempt, 2L, Instant.now());
        when(executionRepository.markFailed(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(AttemptMutationOutcome.OWNERSHIP_LOST);

        AttemptMutationOutcome outcome =
                underTest.markFailedAndCreateRetry(execution, Instant.now().plusSeconds(30), 503, "failure", null, 10L);

        assertEquals(AttemptMutationOutcome.OWNERSHIP_LOST, outcome);
        verify(attemptRepository, never()).save(any());
        verify(attemptRepository, never()).flush();
    }

    @Test
    void createReplay_buildsANewAttemptOneNumberHigherThanTheOriginal_andSavesAndFlushesIt() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Event event = new Event("payment.completed", app);
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        Message message = new Message(app, event, body);
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt original = new Attempt(app, message, endpoint, delivery, 6);
        original.setStatus(AttemptStatus.DEAD);

        // Stub
        when(attemptRepository.saveAndFlush(any(Attempt.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        Attempt replay = underTest.createReplay(original);

        // Assert
        assertEquals(7, replay.getAttemptNo());
        assertEquals(AttemptStatus.CREATED, replay.getStatus());
        assertEquals(original.getMessage().getId(), replay.getMessage().getId());
        assertEquals(original.getEndpoint().getId(), replay.getEndpoint().getId());
        verify(attemptRepository).saveAndFlush(any(Attempt.class));
    }
}
