package com.example.relay.attempt.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.relay.app.domain.App;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptAllocationRepository;
import com.example.relay.attempt.infrastructure.AttemptExecutionClaim;
import com.example.relay.attempt.infrastructure.AttemptExecutionRepository;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.exception.ActiveAttemptAlreadyExistsException;
import com.example.relay.delivery.exception.DeliveryNotDeadException;
import com.example.relay.delivery.exception.DeliveryNotFoundException;
import com.example.relay.delivery.exception.ReplayEndpointInactiveException;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
public class AttemptServiceTest {

    @Mock
    private AttemptRepository attemptRepository;

    @Mock
    private AttemptExecutionRepository executionRepository;

    @Mock
    private DeliveryRepository deliveryRepository;

    @Mock
    private AttemptAllocationRepository allocationRepository;

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
        when(executionRepository.markSucceeded(any(), eq(200), any(), eq(10L))).thenReturn(1);

        AttemptMutationOutcome result = underTest.markSucceeded(execution, 200, responseBody, 10L);

        assertEquals(AttemptMutationOutcome.APPLIED, result);
        verify(executionRepository).markSucceeded(execution, 200, "x".repeat(10_239), 10L);
        verify(attemptRepository, never()).save(any());
        assertEquals(AttemptStatus.CREATED, attempt.getStatus());
    }

    @Test
    void markSucceededRejectsUnexpectedRowCountAndPropagatesRepositoryExceptions() {
        AttemptExecution execution = new AttemptExecution(new Attempt(null, null, null, null, 1), 9L, Instant.now());
        when(executionRepository.markSucceeded(any(), eq(200), any(), eq(10L))).thenReturn(2);

        assertThrows(IllegalStateException.class, () -> underTest.markSucceeded(execution, 200, "body", 10L));

        RuntimeException databaseFailure = new RuntimeException("database unavailable");
        when(executionRepository.markSucceeded(any(), eq(200), any(), eq(10L))).thenThrow(databaseFailure);
        assertEquals(databaseFailure,
                assertThrows(RuntimeException.class, () -> underTest.markSucceeded(execution, 200, "body", 10L)));
    }

    @Test
    void markFailedTruncatesResponseAndErrorBeforeFencedRepositoryCall() {
        AttemptExecution execution = new AttemptExecution(new Attempt(null, null, null, null, 1), 2L, Instant.now());
        Instant nextRetryAt = Instant.now().plusSeconds(30);
        when(executionRepository.markFailed(any(), any(), any(), any(), any(), any(), any())).thenReturn(0);

        AttemptMutationOutcome result = underTest.markFailed(execution, AttemptStatus.FAILED_RETRYING, nextRetryAt, 503,
                "r".repeat(20_000), "e".repeat(20_000), 25L);

        assertEquals(AttemptMutationOutcome.OWNERSHIP_LOST, result);
        verify(executionRepository).markFailed(execution, AttemptStatus.FAILED_RETRYING, nextRetryAt, 503,
                "r".repeat(10_240), "e".repeat(10_240), 25L);
        verify(attemptRepository, never()).save(any());
    }

    @Test
    void markFailedRejectsUnexpectedRowCountAndPropagatesRepositoryExceptions() {
        AttemptExecution execution = new AttemptExecution(new Attempt(null, null, null, null, 1), 2L, Instant.now());
        when(executionRepository.markFailed(any(), any(), any(), any(), any(), any(), any())).thenReturn(2);

        assertThrows(IllegalStateException.class,
                () -> underTest.markFailed(execution, AttemptStatus.DEAD, null, 503, "response", "error", 20L));

        RuntimeException databaseFailure = new RuntimeException("database unavailable");
        when(executionRepository.markFailed(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(databaseFailure);
        assertEquals(databaseFailure, assertThrows(RuntimeException.class,
                () -> underTest.markFailed(execution, AttemptStatus.DEAD, null, 503, "response", "error", 20L)));
    }

    @Test
    void markFailedAndCreateRetryDoesNotCreateRetryWhenExecutionOwnershipIsLost() {
        Attempt attempt = new Attempt(null, null, null, null, 1);
        AttemptExecution execution = new AttemptExecution(attempt, 2L, Instant.now());
        when(executionRepository.markFailed(any(), any(), any(), any(), any(), any(), any())).thenReturn(0);

        AttemptMutationOutcome outcome =
                underTest.markFailedAndCreateRetry(execution, Instant.now().plusSeconds(30), 503, "failure", null, 10L);

        assertEquals(AttemptMutationOutcome.OWNERSHIP_LOST, outcome);
        verify(attemptRepository, never()).save(any());
        verify(attemptRepository, never()).flush();
    }

    @Test
    void createReplay_checksCurrentEligibilityAndAllocatesUnderOrderedParentLocks() {
        Delivery delivery = replayDelivery();
        Attempt latest = new Attempt(delivery.getApp(), delivery.getMessage(), delivery.getEndpoint(), delivery, 6);
        latest.setStatus(AttemptStatus.DEAD);
        when(allocationRepository.lockReplayAllocationParentsIfEndpointActive(delivery.getEndpoint().getId(),
                delivery.getId())).thenReturn(true);
        when(attemptRepository.findFirstByDeliveryIdOrderByAttemptNoDesc(delivery.getId()))
                .thenReturn(Optional.of(latest));
        // The authoritative allocator supplies the number, even when it differs from the loaded Attempt's ordinal.
        when(allocationRepository.nextAttemptNoUnderDeliveryLock(delivery.getId())).thenReturn(9);
        when(attemptRepository.saveAndFlush(any(Attempt.class))).thenAnswer(inv -> inv.getArgument(0));

        Attempt replay = underTest.createReplay(delivery);

        assertEquals(9, replay.getAttemptNo());
        assertEquals(AttemptStatus.CREATED, replay.getStatus());
        assertSame(delivery, replay.getDelivery());
        assertSame(delivery.getMessage(), replay.getMessage());
        assertSame(delivery.getEndpoint(), replay.getEndpoint());
        assertEquals(0, replay.getExecutionGeneration());
        assertNull(replay.getExecutionClaimedAt());
        assertNull(replay.getNextRetryAt());
        var ordered = inOrder(allocationRepository, attemptRepository);
        ordered.verify(allocationRepository).lockReplayAllocationParentsIfEndpointActive(delivery.getEndpoint().getId(),
                delivery.getId());
        ordered.verify(attemptRepository).findFirstByDeliveryIdOrderByAttemptNoDesc(delivery.getId());
        ordered.verify(attemptRepository).existsByMessageIdAndEndpointIdAndStatusIn(delivery.getMessage().getId(),
                delivery.getEndpoint().getId(),
                List.of(AttemptStatus.CREATED, AttemptStatus.IN_FLIGHT, AttemptStatus.SCHEDULED));
        ordered.verify(allocationRepository).nextAttemptNoUnderDeliveryLock(delivery.getId());
        ordered.verify(attemptRepository).saveAndFlush(replay);
        verifyNoInteractions(executionRepository);
    }

    @Test
    void createReplay_rejectsLockedInactiveEndpointBeforeReadingHistory() {
        Delivery delivery = replayDelivery();
        when(allocationRepository.lockReplayAllocationParentsIfEndpointActive(delivery.getEndpoint().getId(),
                delivery.getId())).thenReturn(false);

        assertThrows(ReplayEndpointInactiveException.class, () -> underTest.createReplay(delivery));

        verifyNoInteractions(attemptRepository, executionRepository);
        verify(allocationRepository, never()).nextAttemptNoUnderDeliveryLock(any());
    }

    @Test
    void createReplay_rejectsMissingHistoryAfterLockingParents() {
        Delivery delivery = replayDelivery();
        when(allocationRepository.lockReplayAllocationParentsIfEndpointActive(delivery.getEndpoint().getId(),
                delivery.getId())).thenReturn(true);
        when(attemptRepository.findFirstByDeliveryIdOrderByAttemptNoDesc(delivery.getId()))
                .thenReturn(Optional.empty());

        assertThrows(DeliveryNotFoundException.class, () -> underTest.createReplay(delivery));

        verify(allocationRepository, never()).nextAttemptNoUnderDeliveryLock(any());
        verify(attemptRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(value = AttemptStatus.class, names = "DEAD", mode = EnumSource.Mode.EXCLUDE)
    void createReplay_rejectsCurrentNonDeadBeforeCheckingActiveHistoryOrAllocating(AttemptStatus status) {
        Delivery delivery = replayDelivery();
        Attempt latest = new Attempt(delivery.getApp(), delivery.getMessage(), delivery.getEndpoint(), delivery, 7);
        latest.setStatus(status);
        when(allocationRepository.lockReplayAllocationParentsIfEndpointActive(delivery.getEndpoint().getId(),
                delivery.getId())).thenReturn(true);
        when(attemptRepository.findFirstByDeliveryIdOrderByAttemptNoDesc(delivery.getId()))
                .thenReturn(Optional.of(latest));

        DeliveryNotDeadException rejection =
                assertThrows(DeliveryNotDeadException.class, () -> underTest.createReplay(delivery));

        assertTrue(rejection.getMessage().contains(status.name()));
        verify(attemptRepository, never()).existsByMessageIdAndEndpointIdAndStatusIn(any(), any(), any());
        verify(allocationRepository, never()).nextAttemptNoUnderDeliveryLock(any());
        verify(attemptRepository, never()).saveAndFlush(any());
    }

    @Test
    void createReplay_rejectsContradictoryActiveHistoryWithoutAllocating() {
        Delivery delivery = replayDelivery();
        Attempt latest = new Attempt(delivery.getApp(), delivery.getMessage(), delivery.getEndpoint(), delivery, 6);
        latest.setStatus(AttemptStatus.DEAD);
        when(allocationRepository.lockReplayAllocationParentsIfEndpointActive(delivery.getEndpoint().getId(),
                delivery.getId())).thenReturn(true);
        when(attemptRepository.findFirstByDeliveryIdOrderByAttemptNoDesc(delivery.getId()))
                .thenReturn(Optional.of(latest));
        when(attemptRepository.existsByMessageIdAndEndpointIdAndStatusIn(delivery.getMessage().getId(),
                delivery.getEndpoint().getId(),
                List.of(AttemptStatus.CREATED, AttemptStatus.IN_FLIGHT, AttemptStatus.SCHEDULED))).thenReturn(true);

        assertThrows(ActiveAttemptAlreadyExistsException.class, () -> underTest.createReplay(delivery));

        verify(allocationRepository, never()).nextAttemptNoUnderDeliveryLock(any());
        verify(attemptRepository, never()).saveAndFlush(any());
    }

    @Test
    void createReplay_propagatesInsertInvariantFailureWithoutRetrying() {
        Delivery delivery = replayDelivery();
        Attempt latest = new Attempt(delivery.getApp(), delivery.getMessage(), delivery.getEndpoint(), delivery, 6);
        latest.setStatus(AttemptStatus.DEAD);
        when(allocationRepository.lockReplayAllocationParentsIfEndpointActive(delivery.getEndpoint().getId(),
                delivery.getId())).thenReturn(true);
        when(attemptRepository.findFirstByDeliveryIdOrderByAttemptNoDesc(delivery.getId()))
                .thenReturn(Optional.of(latest));
        when(allocationRepository.nextAttemptNoUnderDeliveryLock(delivery.getId())).thenReturn(7);
        DataIntegrityViolationException failure = new DataIntegrityViolationException("sequence invariant violated");
        when(attemptRepository.saveAndFlush(any())).thenThrow(failure);

        assertSame(failure,
                assertThrows(DataIntegrityViolationException.class, () -> underTest.createReplay(delivery)));

        verify(attemptRepository).saveAndFlush(any());
    }

    private Delivery replayDelivery() {
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Event event = new Event("payment.completed", app);
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        Message message = new Message(app, event, body);
        return new Delivery(app, message, endpoint);
    }
}
