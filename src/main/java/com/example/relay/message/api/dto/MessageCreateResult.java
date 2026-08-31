package com.example.relay.message.api.dto;

import com.example.relay.attempt.domain.Attempt;
import com.example.relay.message.domain.Message;
import java.util.List;

public record MessageCreateResult(Message message, List<Attempt> attempts) {
}
