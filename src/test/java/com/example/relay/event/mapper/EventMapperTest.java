package com.example.relay.event.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.relay.app.domain.App;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.api.dto.EventCreateDto;
import com.example.relay.event.api.dto.EventResponseDto;
import com.example.relay.event.domain.Event;
import com.example.relay.user.domain.User;

public class EventMapperTest {

    private EventMapper underTest;

    @BeforeEach
    void setUp() {
        underTest = new EventMapper();
    }

    @Test
    void toEntity_createsAndReturnsAnEvent() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);

        EventCreateDto request = new EventCreateDto("payment.completed");

        // Act
        Event result = underTest.toEntity(request, app);
        
        // Assert
        assertEquals("payment.completed", result.getName());
        assertEquals(app, result.getApp());
    }

    @Test
    void toResponseDto_mapsEventToEventResponseDtoAndReturns() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);

        // Act
        EventResponseDto result = underTest.toResponseDto(event);

        // Assert
        assertEquals(event.getName(), result.name());
        assertEquals(event.getId(), result.id());
        assertEquals(event.getApp().getId(), result.appId());
        assertEquals(event.getCreatedAt(), result.createdAt());
    }

    @Test
    void toResponseDtoList_mapsListOfEventsToListOfEventResponseDto() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        List<Event> events = List.of(
            new Event("payment.completed", app),
            new Event("user.created", app)
        );

        // Act
        List<EventResponseDto> result = underTest.toResponseDtoList(events);

        // Assert
        assertEquals(events.size(), result.size());
        assertEquals(events.get(0).getId(), result.get(0).id());
        assertEquals(events.get(1).getId(), result.get(1).id());
        assertEquals(events.get(0).getName(), result.get(0).name());
        assertEquals(events.get(1).getName(), result.get(1).name());
        assertEquals(events.get(0).getCreatedAt(), result.get(0).createdAt());
        assertEquals(events.get(1).getCreatedAt(), result.get(1).createdAt());
        assertEquals(events.get(0).getApp().getId(), result.get(0).appId());
        assertEquals(events.get(1).getApp().getId(), result.get(1).appId());
    }
}
