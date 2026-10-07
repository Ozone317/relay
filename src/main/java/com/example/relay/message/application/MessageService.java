package com.example.relay.message.application;

import com.example.relay.app.domain.App;
import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.application.AttemptService;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MessageService {

    private final AppRepository appRepository;
    private final AttemptService attemptService;
    private final EventService eventService;
    private final MessageIdempotencyRepository idempotencyRepository;
    private final MessageRepository messageRepository;
    private final MessageMapper messageMapper;
    private final SubscriptionRepository subscriptionRepository;

    public MessageService(AppRepository appRepository, AttemptService attemptService, EventService eventService,
            MessageIdempotencyRepository idempotencyRepository, MessageRepository messageRepository,
            MessageMapper messageMapper,
            SubscriptionRepository subscriptionRepository) {
        this.appRepository = appRepository;
        this.attemptService = attemptService;
        this.eventService = eventService;
        this.idempotencyRepository = idempotencyRepository;
        this.messageRepository = messageRepository;
        this.messageMapper = messageMapper;
        this.subscriptionRepository = subscriptionRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MessageCreateResult create(MessageCreateDto request, Optional<MessageIdempotencyKey> idempotencyKey,
            UUID appId, UUID environmentId, UUID userId)
            throws AppNotFoundException, EventNotFoundException, NoActiveSubscribersException {
        App app = appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(appId, environmentId, userId)
                .orElseThrow(() -> new AppNotFoundException(appId));

        Event event = eventService.getById(request.eventId(), appId, environmentId, userId);

        if (idempotencyKey.isEmpty()) {
            return acceptWithoutKey(request, app, event);
        }

        MessageIdempotencyKey key = idempotencyKey.orElseThrow();
        UUID proposedMessageId = UUID.randomUUID();
        Optional<MessageIdempotencyAcquisition> acquired = idempotencyRepository.tryAcquire(
                userId, appId, key, proposedMessageId);
        if (acquired.isEmpty()) {
            return replayCommitted(request, appId, environmentId, userId, key);
        }
        return acceptOwnedKey(request, app, event, acquired.orElseThrow());
    }

    MessageCreateResult acceptWithoutKey(MessageCreateDto request, App app, Event event)
            throws NoActiveSubscribersException {
        return createFanout(request, app, event, UUID.randomUUID(), Instant.now());
    }

    MessageCreateResult acceptOwnedKey(MessageCreateDto request, App app, Event event,
            MessageIdempotencyAcquisition acquired) throws NoActiveSubscribersException {
        return createFanout(request, app, event, acquired.messageId(), acquired.acceptedAt());
    }

    MessageCreateResult replayCommitted(MessageCreateDto request, UUID appId, UUID environmentId, UUID userId,
            MessageIdempotencyKey key) {
        CommittedMessageIdempotency committed = idempotencyRepository.findCommittedAndCompare(
                userId, appId, key, request.eventId(), request.body());
        if (!committed.fingerprintMatches()) {
            throw new IdempotencyConflictException();
        }
        Message message = messageRepository.findByIdAndAppIdAndEnvironmentIdAndUserId(
                        committed.messageId(), appId, environmentId, userId)
                .orElseThrow(() -> new IllegalStateException(
                        "committed idempotency authority does not resolve to a scoped Message"));
        return new MessageCreateResult(message);
    }

    MessageCreateResult createFanout(MessageCreateDto request, App app, Event event, UUID messageId,
            Instant acceptedAt) throws NoActiveSubscribersException {
        List<Subscription> subscriptions =
                subscriptionRepository.findAllByEventIdAndEndpointActiveTrue(request.eventId());
        if (subscriptions.isEmpty()) {
            throw new NoActiveSubscribersException(event.getName(), request.eventId());
        }

        // Future accepted-message quota/accounting joins this transaction here. The committed-replay
        // branch returns before this method, so it never consumes another admission.
        Message message = messageMapper.toEntity(request, app, event, messageId, acceptedAt);
        messageRepository.save(message);

        attemptService.createFromSubscriptionList(subscriptions, message);

        return new MessageCreateResult(message);
    }
}
