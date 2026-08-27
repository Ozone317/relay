package com.example.relay.event.api;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.event.api.dto.EventCreateDto;
import com.example.relay.event.api.dto.EventResponseDto;
import com.example.relay.event.application.EventService;
import com.example.relay.event.domain.Event;
import com.example.relay.event.mapper.EventMapper;

import jakarta.validation.Valid;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;


@RestController
@RequestMapping("/api/v1/environments/{environmentId}/apps/{appId}")
public class EventController {

    private final EventMapper eventMapper;
    private final EventService eventService;

    public EventController(EventMapper eventMapper, EventService eventService) {
        this.eventMapper = eventMapper;
        this.eventService = eventService;
    }

    @PostMapping("/events")
    public ResponseEntity<EventResponseDto> create(
        @PathVariable UUID environmentId,
        @PathVariable UUID appId,
        @AuthenticationPrincipal AuthenticatedUser user,
        @RequestBody @Valid EventCreateDto request
    ) {
        Event event = eventService.create(request, appId, environmentId, user.getId());
        EventResponseDto response = eventMapper.toResponseDto(event);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/events")
    public ResponseEntity<List<EventResponseDto>> getAll(
        @PathVariable UUID environmentId,
        @PathVariable UUID appId,
        @AuthenticationPrincipal AuthenticatedUser user
    ) {
        List<Event> events = eventService.getAll(appId, environmentId, user.getId());
        List<EventResponseDto> response = eventMapper.toResponseDtoList(events);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }
}
