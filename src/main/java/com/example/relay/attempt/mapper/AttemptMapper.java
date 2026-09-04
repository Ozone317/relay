package com.example.relay.attempt.mapper;

import org.springframework.stereotype.Component;

import com.example.relay.attempt.api.dto.AttemptDetailDto;
import com.example.relay.attempt.api.dto.AttemptSummaryDto;
import com.example.relay.attempt.domain.Attempt;

@Component
public class AttemptMapper {

    public AttemptSummaryDto toSummaryDto(Attempt attempt) {
        return new AttemptSummaryDto(
            attempt.getId(),
            attempt.getMessage().getEvent().getName(),
            attempt.getEndpoint().getId(),
            attempt.getEndpoint().getName(),
            attempt.getAttemptNo(),
            attempt.getStatus(),
            attempt.getResponseCode(),
            attempt.getLatencyMs(),
            attempt.getCreatedAt()
        );
    }

    public AttemptDetailDto toDetailDto(Attempt attempt) {
        return new AttemptDetailDto(
            attempt.getId(),
            attempt.getMessage().getEvent().getName(),
            attempt.getEndpoint().getId(),
            attempt.getEndpoint().getName(),
            attempt.getMessage().getId(),
            attempt.getMessage().getBody(),
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
