package com.example.relay.event.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.example.relay.app.domain.App;
import com.example.relay.event.api.dto.EventCreateDto;
import com.example.relay.event.api.dto.EventResponseDto;
import com.example.relay.event.domain.Event;

import lombok.NoArgsConstructor;

@Component
@NoArgsConstructor
public class EventMapper {

    public Event toEntity(EventCreateDto request, App app) {
        return new Event(
            request.name(),
            app
        );
    }

    public EventResponseDto toResponseDto(Event event) {
        return new EventResponseDto(
            event.getId(),
            event.getName(),
            event.getApp().getId(),
            event.getCreatedAt(),
            0
        );
    }

    public List<EventResponseDto> toResponseDtoList(List<Event> events) {
        return events.stream()
        .map(this::toResponseDto).
        toList();
    }
}
