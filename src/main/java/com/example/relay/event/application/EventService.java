package com.example.relay.event.application;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.example.relay.app.domain.App;
import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.event.api.dto.EventCreateDto;
import com.example.relay.event.domain.Event;
import com.example.relay.event.exception.EventAlreadyExistsException;
import com.example.relay.event.infrastructure.EventRepository;
import com.example.relay.event.mapper.EventMapper;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final AppRepository appRepository;
    private final EventMapper eventMapper;

    public EventService(
        EventMapper eventMapper,
        EventRepository eventRepository,
        AppRepository appRepository
    ) {
        this.appRepository = appRepository;
        this.eventRepository = eventRepository;
        this.eventMapper = eventMapper;
    }

    public Event create(EventCreateDto request, UUID appId, UUID userId) throws AppNotFoundException, EventAlreadyExistsException {
        App app = appRepository.findByIdAndEnvironmentUserId(
            appId,
            userId
        ).orElseThrow(
            () -> new AppNotFoundException(appId)
        );

        eventRepository.findByNameAndAppId(
            request.name(),
            appId
        ).ifPresent(
            event -> {
                throw new EventAlreadyExistsException(request.name());
            }
        );

        Event toCreate = eventMapper.toEntity(request, app);
        
        try {
            return eventRepository.saveAndFlush(toCreate);
        } catch (DataIntegrityViolationException ex) {
            throw new EventAlreadyExistsException(request.name());
        }
    }

    public List<Event> getAll(UUID appId, UUID userId) {
        appRepository.findByIdAndEnvironmentUserId(appId, userId)
        .orElseThrow(
            () -> new AppNotFoundException(appId)
        );

        List<Event> events = eventRepository.findAllByAppId(appId);
        
        return events;
    }
}
