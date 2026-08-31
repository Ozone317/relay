package com.example.relay.message.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.relay.app.domain.App;
import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.application.EventService;
import com.example.relay.event.domain.Event;
import com.example.relay.event.exception.EventNotFoundException;
import com.example.relay.message.api.dto.MessageCreateDto;
import com.example.relay.message.api.dto.MessageCreateResult;
import com.example.relay.message.domain.Message;
import com.example.relay.message.exception.NoActiveSubscribersException;
import com.example.relay.message.infrastructure.MessageRepository;
import com.example.relay.message.mapper.MessageMapper;
import com.example.relay.subscription.domain.Subscription;
import com.example.relay.subscription.infrastructure.SubscriptionRepository;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class MessageServiceTest {

    @Mock
    private AppRepository appRepository;

    @Mock
    private AttemptService attemptService;

    @Mock
    private EventService eventService;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @InjectMocks
    private MessageService underTest;

    @Test
    void create_createsMessageAndTriggersAttemptCreation_whenAppEventAndActiveSubscribersExist() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Event event = new Event("payment.completed", app);
        Subscription subscription = new Subscription(app, event, endpoint);
        List<Subscription> subscriptions = List.of(subscription);
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        MessageCreateDto request = new MessageCreateDto(event.getId(), body);
        Message message = new Message(app, event, body);
        List<Attempt> attempts = List.of(new Attempt(app, message, endpoint, 1));

        // Stubs
        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId()))
                .thenReturn(Optional.of(app));
        when(eventService.getById(event.getId(), app.getId(), env.getId(), user.getId())).thenReturn(event);
        when(subscriptionRepository.findAllByEventIdAndEndpointActiveTrue(event.getId())).thenReturn(subscriptions);
        when(messageMapper.toEntity(request, app, event)).thenReturn(message);
        when(attemptService.createFromSubscriptionList(subscriptions, message)).thenReturn(attempts);

        // Act
        MessageCreateResult result = underTest.create(request, app.getId(), env.getId(), user.getId());

        // Assert
        assertEquals(message.getId(), result.message().getId());
        assertEquals(attempts, result.attempts());

        // Verify
        verify(messageRepository).save(message);
        verify(attemptService).createFromSubscriptionList(subscriptions, message);
    }

    @Test
    void create_throwsAppNotFoundException_whenAppDoesNotExistOrDoesNotBelongToUser() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        UUID eventId = UUID.randomUUID();
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        MessageCreateDto request = new MessageCreateDto(eventId, body);

        // Stub
        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId()))
                .thenReturn(Optional.empty());

        // Act + Assert
        assertThrows(AppNotFoundException.class,
                () -> underTest.create(request, app.getId(), env.getId(), user.getId()));

        // Verify
        verify(eventService, never()).getById(any(), any(), any(), any());
        verify(messageRepository, never()).save(any());
        verify(attemptService, never()).createFromSubscriptionList(any(), any());
    }

    @Test
    void create_throwsEventNotFoundException_whenEventDoesNotBelongToApp() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        UUID eventId = UUID.randomUUID();
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        MessageCreateDto request = new MessageCreateDto(eventId, body);

        // Stubs
        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId()))
                .thenReturn(Optional.of(app));
        when(eventService.getById(eventId, app.getId(), env.getId(), user.getId()))
                .thenThrow(new EventNotFoundException(eventId));

        // Act + Assert
        assertThrows(EventNotFoundException.class,
                () -> underTest.create(request, app.getId(), env.getId(), user.getId()));

        // Verify
        verify(messageRepository, never()).save(any());
        verify(attemptService, never()).createFromSubscriptionList(any(), any());
    }

    @Test
    void create_throwsNoActiveSubscribersException_whenNoActiveEndpointsAreSubscribedToTheEvent() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        MessageCreateDto request = new MessageCreateDto(event.getId(), body);

        // Stubs
        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId()))
                .thenReturn(Optional.of(app));
        when(eventService.getById(event.getId(), app.getId(), env.getId(), user.getId())).thenReturn(event);
        when(subscriptionRepository.findAllByEventIdAndEndpointActiveTrue(event.getId())).thenReturn(List.of());

        // Act + Assert
        assertThrows(NoActiveSubscribersException.class,
                () -> underTest.create(request, app.getId(), env.getId(), user.getId()));

        // Verify
        verify(messageRepository, never()).save(any());
        verify(attemptService, never()).createFromSubscriptionList(any(), any());
    }
}
