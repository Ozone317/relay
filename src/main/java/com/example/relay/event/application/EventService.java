package com.example.relay.event.application;

import com.example.relay.app.domain.App;
import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.event.api.dto.EventCreateDto;
import com.example.relay.event.api.dto.EventResponseDto;
import com.example.relay.event.domain.Event;
import com.example.relay.event.exception.EventAlreadyExistsException;
import com.example.relay.event.exception.EventNotFoundException;
import com.example.relay.event.infrastructure.EventRepository;
import com.example.relay.event.mapper.EventMapper;
import com.example.relay.subscription.infrastructure.SubscriptionRepository;
import com.example.relay.subscription.infrastructure.SubscriptionRepository.EventSubscriptionCount;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final AppRepository appRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final EventMapper eventMapper;

    public EventService(EventMapper eventMapper, EventRepository eventRepository, AppRepository appRepository,
            SubscriptionRepository subscriptionRepository) {
        this.appRepository = appRepository;
        this.eventRepository = eventRepository;
        this.eventMapper = eventMapper;
        this.subscriptionRepository = subscriptionRepository;
    }

    public Event create(EventCreateDto request, UUID appId, UUID environmentId, UUID userId)
            throws AppNotFoundException, EventAlreadyExistsException {
        App app = appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(appId, environmentId, userId)
                .orElseThrow(() -> new AppNotFoundException(appId));

        eventRepository.findByNameAndAppId(request.name(), appId).ifPresent(event -> {
            throw new EventAlreadyExistsException(request.name());
        });

        Event toCreate = eventMapper.toEntity(request, app);

        try {
            return eventRepository.saveAndFlush(toCreate);
        } catch (DataIntegrityViolationException ex) {
            throw new EventAlreadyExistsException(request.name());
        }
    }

    public List<EventResponseDto> getAll(UUID appId, UUID environmentId, UUID userId) {
        appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(appId, environmentId, userId)
                .orElseThrow(() -> new AppNotFoundException(appId));

        List<Event> events = eventRepository.findAllByAppId(appId);
        List<UUID> eventIds = events.stream().map((event) -> event.getId()).toList();
        List<EventSubscriptionCount> eventSubscriptionCounts =
                subscriptionRepository.countByEventIdIn(appId, environmentId, eventIds, userId);
        Map<UUID, Long> eventIdCountMap = eventSubscriptionCounts.stream()
                .collect(Collectors.toMap(EventSubscriptionCount::getEventId, EventSubscriptionCount::getCount));
        List<EventResponseDto> response = eventMapper.toResponseDtoList(events, eventIdCountMap);

        return response;
    }

    public Event getById(UUID id, UUID appId, UUID environmentId, UUID userId) {
        Event event = eventRepository.findByIdAndAppIdAndEnvironmentIdAndUserId(id, appId, environmentId, userId)
                .orElseThrow(() -> new EventNotFoundException(id));

        return event;
    }

    public EventResponseDto getResponseById(UUID id, UUID appId, UUID environmentId, UUID userId) {
        Event event = getById(id, appId, environmentId, userId);

        long subscriberCount = subscriptionRepository.countByAppIdAndEnvironmentIdAndEventIdAndUserId(appId,
                environmentId, id, userId);

        return eventMapper.toResponseDto(event, subscriberCount);
    }
}
