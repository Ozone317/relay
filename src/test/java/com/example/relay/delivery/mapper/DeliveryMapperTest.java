package com.example.relay.delivery.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.delivery.api.dto.DeliveryDetailDto;
import com.example.relay.delivery.api.dto.DeliverySummaryDto;
import com.example.relay.delivery.domain.DeliveryStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveryMapperTest {

    private final DeliveryMapper underTest = new DeliveryMapper();

    private DeliveryStatus buildStatus() throws Exception {
        DeliveryStatus status = new DeliveryStatus();
        set(status, "deliveryId", UUID.randomUUID());
        set(status, "endpointId", UUID.randomUUID());
        set(status, "endpointName", "Production");
        set(status, "eventName", "payment.completed");
        set(status, "messageId", UUID.randomUUID());
        set(status, "deliveryCreatedAt", Instant.now());
        set(status, "attemptNo", 3);
        set(status, "status", AttemptStatus.SCHEDULED);
        set(status, "responseCode", 500);
        set(status, "latencyMs", 120L);
        set(status, "lastAttemptAt", Instant.now());
        set(status, "attemptCount", 3L);
        return status;
    }

    private void set(Object target, String field, Object value) throws Exception {
        Field f = DeliveryStatus.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void toSummaryDto_mapsEveryField() throws Exception {
        DeliveryStatus status = buildStatus();

        DeliverySummaryDto dto = underTest.toSummaryDto(status);

        assertEquals(status.getDeliveryId(), dto.id());
        assertEquals(status.getEventName(), dto.eventName());
        assertEquals(status.getEndpointId(), dto.endpointId());
        assertEquals(status.getEndpointName(), dto.endpointName());
        assertEquals(status.getStatus(), dto.status());
        assertEquals(status.getAttemptCount(), dto.attemptCount());
        assertEquals(status.getAttemptNo(), dto.latestAttemptNo());
        assertEquals(status.getResponseCode(), dto.responseCode());
        assertEquals(status.getLatencyMs(), dto.latencyMs());
        assertEquals(status.getDeliveryCreatedAt(), dto.createdAt());
        assertEquals(status.getLastAttemptAt(), dto.lastAttemptAt());
    }

    @Test
    void toDetailDto_mapsEveryFieldIncludingThePayload() throws Exception {
        DeliveryStatus status = buildStatus();
        ObjectNode payload = new ObjectMapper().createObjectNode().put("amount", 4999);

        DeliveryDetailDto dto = underTest.toDetailDto(status, payload);

        assertEquals(status.getDeliveryId(), dto.id());
        assertEquals(status.getMessageId(), dto.messageId());
        assertEquals(payload, dto.payload());
        assertEquals(status.getStatus(), dto.status());
        assertNull(new DeliveryDetailDto(null, null, null, null, null, null, null, 0, 0, null, null, null, null)
                .payload());
    }
}
