package com.example.relay.attempt.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.relay.app.domain.App;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
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

        // Act
        List<Attempt> result = underTest.createFromSubscriptionList(subscriptions, message);

        // Assert
        assertEquals(2, result.size());
        for (Attempt attempt : result) {
            assertEquals(1, attempt.getAttemptNo());
            assertEquals(AttemptStatus.CREATED, attempt.getStatus());
            assertEquals(message.getId(), attempt.getMessage().getId());
            assertEquals(app.getId(), attempt.getApp().getId());
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
    void claim_returnsTrue_whenTheAttemptIsClaimedSuccessfully() {
        // Arrange
        UUID attemptId = UUID.randomUUID();

        // Stub
        when(attemptRepository.claim(attemptId)).thenReturn(1);

        // Act
        boolean result = underTest.claim(attemptId);

        // Assert
        assertEquals(true, result);
    }

    @Test
    void claim_returnsFalse_whenTheAttemptIsNotClaimedSuccessfully() {
        // Arrange
        UUID attemptId = UUID.randomUUID();

        // Stub
        when(attemptRepository.claim(attemptId)).thenReturn(0);

        // Act
        boolean result = underTest.claim(attemptId);

        // Assert
        assertEquals(false, result);
    }

    @Test
    void createRetry_createsSavesAndReturnsAttemptWithIncreasedAttemptCount() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        Message message = new Message(app, event, body);
        Endpoint endpoint = new Endpoint("staging", "https://webhook.com", "whsec_some_secret", app);
        Attempt attempt = new Attempt(app, message, endpoint, 1);

        // Stub
        // this says that i dont care which object the attemptRepository is passed, give me the exact same object back
        when(attemptRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Attempt result = underTest.createRetry(attempt);

        // Assert
        assertEquals(attempt.getAttemptNo() + 1, result.getAttemptNo());
        assertEquals(AttemptStatus.CREATED, result.getStatus());
    }

    @Test
    void markSucceeded_marksTheAttemptAsSuccessfulSetsRequiredFilesAndReturnsTheAttempt() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        Message message = new Message(app, event, body);
        Endpoint endpoint = new Endpoint("staging", "https://webhook.com", "whsec_some_secret", app);
        Attempt attempt = new Attempt(app, message, endpoint, 1);
        int responseCode = 200;
        String responseBody = "{\"success\": \"true\"}";
        Long latencyMs = 153L;

        // Stub
        when(attemptRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Attempt result = underTest.markSucceeded(attempt, responseCode, responseBody, latencyMs);

        // Assert
        assertEquals(AttemptStatus.SUCCEEDED, result.getStatus());
        assertEquals(responseCode, result.getResponseCode());
        assertEquals(responseBody, result.getResponseBody());
        assertEquals(latencyMs, result.getLatencyMs());

        // Verify
        verify(attemptRepository).save(any());
    }

    @Test
    void markFailed_marksTheAttemptWithTheGivenFailedStatusSetsRequiredFieldsAndReturnsTheAttemp() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        Message message = new Message(app, event, body);
        Endpoint endpoint = new Endpoint("staging", "https://webhook.com", "whsec_some_secret", app);
        Attempt attempt = new Attempt(app, message, endpoint, 1);
        AttemptStatus status = AttemptStatus.FAILED_RETRYING;
        Instant nextRetryAt = Instant.now();
        int responseCode = 500;
        String error = "x".repeat(20_000);
        Long latencyMs = 15000L;

        // Stub
        when(attemptRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Attempt result = underTest.markFailed(attempt, status, nextRetryAt, responseCode, error, latencyMs);

        // Assert
        assertEquals(status, result.getStatus());
        assertEquals(nextRetryAt, result.getNextRetryAt());
        assertEquals(responseCode, result.getResponseCode());
        assertEquals(10240, result.getLastError().length());
        assertEquals(latencyMs, result.getLatencyMs());

        // Verify
        verify(attemptRepository).save(any());
    }
}
