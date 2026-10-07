package com.example.relay.message.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.relay.app.domain.App;
import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.application.EventService;
import com.example.relay.event.domain.Event;
import com.example.relay.event.exception.EventNotFoundException;
import com.example.relay.message.api.MessageIdempotencyKey;
import com.example.relay.message.api.dto.MessageCreateDto;
import com.example.relay.message.api.dto.MessageCreateResult;
import com.example.relay.message.domain.Message;
import com.example.relay.message.exception.IdempotencyConflictException;
import com.example.relay.message.exception.NoActiveSubscribersException;
import com.example.relay.message.infrastructure.CommittedMessageIdempotency;
import com.example.relay.message.infrastructure.MessageIdempotencyAcquisition;
import com.example.relay.message.infrastructure.MessageIdempotencyRepository;
import com.example.relay.message.infrastructure.MessageRepository;
import com.example.relay.message.mapper.MessageMapper;
import com.example.relay.subscription.domain.Subscription;
import com.example.relay.subscription.infrastructure.SubscriptionRepository;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock private AppRepository appRepository;
    @Mock private AttemptService attemptService;
    @Mock private EventService eventService;
    @Mock private MessageRepository messageRepository;
    @Mock private MessageIdempotencyRepository idempotencyRepository;
    @Mock private MessageMapper messageMapper;
    @Mock private SubscriptionRepository subscriptionRepository;

    @InjectMocks private MessageService underTest;

    private User user;
    private Environment env;
    private App app;
    private Event event;
    private MessageCreateDto request;
    private List<Subscription> subscriptions;

    @BeforeEach
    void setUp() {
        user = new User("test@mail.com", "someHash");
        env = new Environment("Env 1", "Desc 1", user);
        app = new App("App 1", env);
        event = new Event("payment.completed", app);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        subscriptions = List.of(new Subscription(app, event, endpoint));
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        request = new MessageCreateDto(event.getId(), body);

        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId()))
                .thenReturn(Optional.of(app));
        when(eventService.getById(event.getId(), app.getId(), env.getId(), user.getId())).thenReturn(event);
    }

    @Test
    void createWithoutKeyCreatesOneMessageAndFanoutWithoutIdempotencyAccess() {
        Message message = new Message(app, event, request.body());
        when(subscriptionRepository.findAllByEventIdAndEndpointActiveTrue(event.getId())).thenReturn(subscriptions);
        when(messageMapper.toEntity(any(), any(), any(), any(), any())).thenReturn(message);

        MessageCreateResult result = underTest.create(request, Optional.empty(), app.getId(), env.getId(), user.getId());

        assertSame(message, result.message());
        verify(idempotencyRepository, never()).tryAcquire(any(), any(), any(), any());
        verify(idempotencyRepository, never()).findCommittedAndCompare(any(), any(), any(), any(), any());
        verify(subscriptionRepository).findAllByEventIdAndEndpointActiveTrue(event.getId());
        verify(messageRepository).save(message);
        verify(attemptService).createFromSubscriptionList(subscriptions, message);
    }

    @Test
    void createWithKeyedOwnerUsesAcquiredIdentityAndRunsFanoutOnce() {
        MessageIdempotencyKey key = new MessageIdempotencyKey("request-1");
        Instant acceptedAt = Instant.parse("2026-10-06T12:30:00Z");
        when(subscriptionRepository.findAllByEventIdAndEndpointActiveTrue(event.getId())).thenReturn(subscriptions);
        when(idempotencyRepository.tryAcquire(eq(user.getId()), eq(app.getId()), eq(key), any(UUID.class)))
                .thenAnswer(invocation -> Optional.of(new MessageIdempotencyAcquisition(
                        invocation.getArgument(3), acceptedAt)));
        when(messageMapper.toEntity(eq(request), eq(app), eq(event), any(UUID.class), eq(acceptedAt)))
                .thenAnswer(invocation -> new Message(invocation.getArgument(3), app, event, request.body(), acceptedAt));

        MessageCreateResult result = underTest.create(request, Optional.of(key), app.getId(), env.getId(), user.getId());

        assertEquals(acceptedAt, result.message().getCreatedAt());
        verify(idempotencyRepository).tryAcquire(eq(user.getId()), eq(app.getId()), eq(key), eq(result.message().getId()));
        verify(messageRepository).save(result.message());
        verify(attemptService).createFromSubscriptionList(subscriptions, result.message());
        verify(subscriptionRepository).findAllByEventIdAndEndpointActiveTrue(event.getId());
    }

    @Test
    void createReturnsCommittedReplayWithoutResolvingSubscribersOrCreatingAttempts() {
        MessageIdempotencyKey key = new MessageIdempotencyKey("request-1");
        UUID originalId = UUID.randomUUID();
        Instant acceptedAt = Instant.parse("2026-10-06T12:30:00Z");
        Message original = new Message(originalId, app, event, request.body(), acceptedAt);
        when(idempotencyRepository.tryAcquire(eq(user.getId()), eq(app.getId()), eq(key), any(UUID.class)))
                .thenReturn(Optional.empty());
        when(idempotencyRepository.findCommittedAndCompare(user.getId(), app.getId(), key, event.getId(), request.body()))
                .thenReturn(new CommittedMessageIdempotency(originalId, acceptedAt, (short) 1, true));
        when(messageRepository.findByIdAndAppIdAndEnvironmentIdAndUserId(originalId, app.getId(), env.getId(), user.getId()))
                .thenReturn(Optional.of(original));

        MessageCreateResult result = underTest.create(request, Optional.of(key), app.getId(), env.getId(), user.getId());

        assertSame(original, result.message());
        verify(subscriptionRepository, never()).findAllByEventIdAndEndpointActiveTrue(any());
        verify(attemptService, never()).createFromSubscriptionList(any(), any());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void createWithConflictingReplayThrowsBeforeFanout() {
        MessageIdempotencyKey key = new MessageIdempotencyKey("request-1");
        UUID originalId = UUID.randomUUID();
        when(idempotencyRepository.tryAcquire(eq(user.getId()), eq(app.getId()), eq(key), any(UUID.class)))
                .thenReturn(Optional.empty());
        when(idempotencyRepository.findCommittedAndCompare(user.getId(), app.getId(), key, event.getId(), request.body()))
                .thenReturn(new CommittedMessageIdempotency(originalId, Instant.now(), (short) 1, false));

        assertThrows(IdempotencyConflictException.class,
                () -> underTest.create(request, Optional.of(key), app.getId(), env.getId(), user.getId()));

        verify(messageRepository, never()).findByIdAndAppIdAndEnvironmentIdAndUserId(any(), any(), any(), any());
        verify(subscriptionRepository, never()).findAllByEventIdAndEndpointActiveTrue(any());
        verify(attemptService, never()).createFromSubscriptionList(any(), any());
    }

    @Test
    void createWithMatchingAuthorityButMissingScopedMessageFailsLoudly() {
        MessageIdempotencyKey key = new MessageIdempotencyKey("request-1");
        UUID originalId = UUID.randomUUID();
        when(idempotencyRepository.tryAcquire(eq(user.getId()), eq(app.getId()), eq(key), any(UUID.class)))
                .thenReturn(Optional.empty());
        when(idempotencyRepository.findCommittedAndCompare(user.getId(), app.getId(), key, event.getId(), request.body()))
                .thenReturn(new CommittedMessageIdempotency(originalId, Instant.now(), (short) 1, true));

        assertThrows(IllegalStateException.class,
                () -> underTest.create(request, Optional.of(key), app.getId(), env.getId(), user.getId()));

        verify(messageRepository).findByIdAndAppIdAndEnvironmentIdAndUserId(originalId, app.getId(), env.getId(), user.getId());
        verify(subscriptionRepository, never()).findAllByEventIdAndEndpointActiveTrue(any());
    }

    @Test
    void createAuthorizesAppAndEventBeforeAttemptingKeyAcquisition() {
        MessageIdempotencyKey key = new MessageIdempotencyKey("request-1");
        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId()))
                .thenReturn(Optional.empty());

        assertThrows(AppNotFoundException.class,
                () -> underTest.create(request, Optional.of(key), app.getId(), env.getId(), user.getId()));
        verify(idempotencyRepository, never()).tryAcquire(any(), any(), any(), any());

        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId()))
                .thenReturn(Optional.of(app));
        when(eventService.getById(event.getId(), app.getId(), env.getId(), user.getId()))
                .thenThrow(new EventNotFoundException(event.getId()));

        assertThrows(EventNotFoundException.class,
                () -> underTest.create(request, Optional.of(key), app.getId(), env.getId(), user.getId()));
        verify(idempotencyRepository, never()).tryAcquire(any(), any(), any(), any());
    }

    @Test
    void createWithKeyedOwnerWithoutSubscribersThrowsAndDoesNotPersistMessage() {
        MessageIdempotencyKey key = new MessageIdempotencyKey("request-1");
        UUID messageId = UUID.randomUUID();
        Instant acceptedAt = Instant.now();
        when(subscriptionRepository.findAllByEventIdAndEndpointActiveTrue(event.getId())).thenReturn(List.of());
        when(idempotencyRepository.tryAcquire(eq(user.getId()), eq(app.getId()), eq(key), any(UUID.class)))
                .thenReturn(Optional.of(new MessageIdempotencyAcquisition(messageId, acceptedAt)));

        assertThrows(NoActiveSubscribersException.class,
                () -> underTest.create(request, Optional.of(key), app.getId(), env.getId(), user.getId()));

        verify(messageRepository, never()).save(any());
        verify(attemptService, never()).createFromSubscriptionList(any(), any());
    }
}
