package com.example.relay.message.mapper;

import com.example.relay.app.domain.App;
import com.example.relay.event.domain.Event;
import com.example.relay.message.api.dto.MessageCreateDto;
import com.example.relay.message.api.dto.MessageResponseDto;
import com.example.relay.message.domain.Message;
import org.springframework.stereotype.Component;

@Component
public class MessageMapper {

    public Message toEntity(MessageCreateDto request, App app, Event event) {
        return new Message(app, event, request.body());
    }

    public MessageResponseDto toResponseDto(Message message) {
        return new MessageResponseDto(message.getId(), message.getApp().getId(), message.getEvent().getId(),
                message.getEvent().getName(), message.getBody(), message.getCreatedAt());
    }
}
