package com.example.relay.delivery.mapper;

import com.example.relay.delivery.api.dto.DeliveryDetailDto;
import com.example.relay.delivery.api.dto.DeliverySummaryDto;
import com.example.relay.delivery.domain.DeliveryStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class DeliveryMapper {

    public DeliverySummaryDto toSummaryDto(DeliveryStatus status) {
        return new DeliverySummaryDto(
            status.getDeliveryId(),
            status.getEventName(),
            status.getEndpointId(),
            status.getEndpointName(),
            status.getStatus(),
            status.getAttemptCount(),
            status.getAttemptNo(),
            status.getResponseCode(),
            status.getLatencyMs(),
            status.getDeliveryCreatedAt(),
            status.getLastAttemptAt()
        );
    }

    public DeliveryDetailDto toDetailDto(DeliveryStatus status, JsonNode payload) {
        return new DeliveryDetailDto(
            status.getDeliveryId(),
            status.getEventName(),
            status.getEndpointId(),
            status.getEndpointName(),
            status.getMessageId(),
            payload,
            status.getStatus(),
            status.getAttemptCount(),
            status.getAttemptNo(),
            status.getResponseCode(),
            status.getLatencyMs(),
            status.getDeliveryCreatedAt(),
            status.getLastAttemptAt()
        );
    }
}
