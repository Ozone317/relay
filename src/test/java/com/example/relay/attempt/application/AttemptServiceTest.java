package com.example.relay.attempt.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
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
import java.util.List;
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
}
