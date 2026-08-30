package com.example.relay.event.mapper;

import com.example.relay.app.domain.App;
import com.example.relay.event.api.dto.EventCreateDto;
import com.example.relay.event.api.dto.EventResponseDto;
import com.example.relay.event.domain.Event;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@NoArgsConstructor
public class EventMapper {

    public Event toEntity(EventCreateDto request, App app) {
        return new Event(request.name(), app);
    }

    public EventResponseDto toResponseDto(Event event, long subscriberCount) {
        return new EventResponseDto(event.getId(), event.getName(), event.getApp().getId(), event.getCreatedAt(),
                subscriberCount);
    }

    public List<EventResponseDto> toResponseDtoList(List<Event> events, Map<UUID, Long> eventIdCountMap) {
        return events.stream().map((event) -> toResponseDto(event, eventIdCountMap.getOrDefault(event.getId(), 0L)))
                .toList();
    }
}
