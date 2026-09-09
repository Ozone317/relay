package com.example.relay.attempt.mapper;

import org.springframework.stereotype.Component;

import com.example.relay.attempt.api.dto.AttemptDetailDto;
import com.example.relay.attempt.domain.Attempt;

@Component
public class AttemptMapper {

    public AttemptDetailDto toDetailDto(Attempt attempt) {
        return new AttemptDetailDto(
            attempt.getId(),
            attempt.getDelivery().getId(),
            attempt.getAttemptNo(),
            attempt.getStatus(),
            attempt.getResponseCode(),
            attempt.getResponseBody(),
            attempt.getLastError(),
            attempt.getLatencyMs(),
            attempt.getNextRetryAt(),
            attempt.getCreatedAt(),
            attempt.getUpdatedAt()
        );
    }
}
