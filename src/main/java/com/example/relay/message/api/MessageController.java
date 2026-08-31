package com.example.relay.message.api;

import com.example.relay.attempt.domain.Attempt;
import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.delivery.publisher.AttemptPublisher;
import com.example.relay.message.api.dto.MessageCreateDto;
import com.example.relay.message.api.dto.MessageCreateResult;
import com.example.relay.message.api.dto.MessageResponseDto;
import com.example.relay.message.application.MessageService;
import com.example.relay.message.domain.Message;
import com.example.relay.message.mapper.MessageMapper;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/environments/{environmentId}/apps/{appId}")
public class MessageController {

    private final AttemptPublisher attemptPublisher;
    private final MessageMapper messageMapper;
    private final MessageService messageService;

    public MessageController(AttemptPublisher attemptPublisher, MessageMapper messageMapper,
            MessageService messageService) {
        this.attemptPublisher = attemptPublisher;
        this.messageMapper = messageMapper;
        this.messageService = messageService;
    }

    @PostMapping("/messages")
    public ResponseEntity<MessageResponseDto> create(@PathVariable UUID environmentId, @PathVariable UUID appId,
            @AuthenticationPrincipal AuthenticatedUser user, @RequestBody @Valid MessageCreateDto request) {
        MessageCreateResult result = messageService.create(request, appId, environmentId, user.getId());

        for (Attempt attempt : result.attempts()) {
            attemptPublisher.publish(attempt.getId());
        }

        Message message = result.message();
        MessageResponseDto response = messageMapper.toResponseDto(message);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

}
