package com.example.relay.message.application;

import com.example.relay.app.domain.App;
import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
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
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MessageService {

    private final AppRepository appRepository;
    private final AttemptService attemptService;
    private final EventService eventService;
    private final MessageRepository messageRepository;
    private final MessageMapper messageMapper;
    private final SubscriptionRepository subscriptionRepository;

    public MessageService(AppRepository appRepository, AttemptService attemptService, EventService eventService,
            MessageRepository messageRepository, MessageMapper messageMapper,
            SubscriptionRepository subscriptionRepository) {
        this.appRepository = appRepository;
        this.attemptService = attemptService;
        this.eventService = eventService;
        this.messageRepository = messageRepository;
        this.messageMapper = messageMapper;
        this.subscriptionRepository = subscriptionRepository;
    }

    @Transactional
    public MessageCreateResult create(MessageCreateDto request, UUID appId, UUID environmentId, UUID userId)
            throws AppNotFoundException, EventNotFoundException, NoActiveSubscribersException {
        App app = appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(appId, environmentId, userId)
                .orElseThrow(() -> new AppNotFoundException(appId));

        Event event = eventService.getById(request.eventId(), appId, environmentId, userId);

        List<Subscription> subscriptions =
                subscriptionRepository.findAllByEventIdAndEndpointActiveTrue(request.eventId());
        if (subscriptions.isEmpty()) {
            throw new NoActiveSubscribersException(event.getName(), request.eventId());
        }

        Message message = messageMapper.toEntity(request, app, event);
        messageRepository.save(message);

        List<Attempt> attempts = attemptService.createFromSubscriptionList(subscriptions, message);

        return new MessageCreateResult(message, attempts);
    }
}
