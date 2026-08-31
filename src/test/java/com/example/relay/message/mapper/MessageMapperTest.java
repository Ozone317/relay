package com.example.relay.message.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.relay.app.domain.App;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.message.api.dto.MessageCreateDto;
import com.example.relay.message.api.dto.MessageResponseDto;
import com.example.relay.message.domain.Message;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MessageMapperTest {

    private MessageMapper underTest;

    @BeforeEach
    void setUp() {
        underTest = new MessageMapper();
    }

    @Test
    void toEntity_createsAndReturnsAMessage() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        MessageCreateDto request = new MessageCreateDto(event.getId(), body);

        // Act
        Message result = underTest.toEntity(request, app, event);

        // Assert
        assertEquals(app, result.getApp());
        assertEquals(event, result.getEvent());
        assertEquals(body, result.getBody());
    }

    @Test
    void toResponseDto_mapsMessageToMessageResponseDtoAndReturns() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        Message message = new Message(app, event, body);

        // Act
        MessageResponseDto result = underTest.toResponseDto(message);

        // Assert
        assertEquals(message.getId(), result.id());
        assertEquals(app.getId(), result.appId());
        assertEquals(event.getId(), result.eventId());
        assertEquals(event.getName(), result.eventName());
        assertEquals(body, result.body());
        assertEquals(message.getCreatedAt(), result.createdAt());
    }
}
