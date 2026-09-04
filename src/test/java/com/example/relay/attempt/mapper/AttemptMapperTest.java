package com.example.relay.attempt.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.relay.app.domain.App;
import com.example.relay.attempt.api.dto.AttemptDetailDto;
import com.example.relay.attempt.api.dto.AttemptSummaryDto;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.message.domain.Message;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class AttemptMapperTest {

    private AttemptMapper underTest;

    @BeforeEach
    void setUp() {
        underTest = new AttemptMapper();
    }

    @Test
    void toSummaryDto_mapsAttemptToAttemptSummaryDto() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_test", app);
        JsonNode body = new ObjectMapper().readTree("{\"amount\": 4999}");
        Message message = new Message(app, event, body);
        Attempt attempt = new Attempt(app, message, endpoint, 1);
        attempt.setStatus(AttemptStatus.SUCCEEDED);
        attempt.setResponseCode(200);
        attempt.setLatencyMs(120L);

        // Act
        AttemptSummaryDto result = underTest.toSummaryDto(attempt);

        // Assert
        assertEquals(attempt.getId(), result.id());
        assertEquals("payment.completed", result.eventName());
        assertEquals(endpoint.getId(), result.endpointId());
        assertEquals("Production", result.endpointName());
        assertEquals(1, result.attemptNo());
        assertEquals(AttemptStatus.SUCCEEDED, result.status());
        assertEquals(200, result.responseCode());
        assertEquals(120L, result.latencyMs());
        assertEquals(attempt.getCreatedAt(), result.createdAt());
    }

    @Test
    void toDetailDto_mapsAttemptToAttemptDetailDto() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_test", app);
        JsonNode body = new ObjectMapper().readTree("{\"amount\": 4999}");
        Message message = new Message(app, event, body);
        Attempt attempt = new Attempt(app, message, endpoint, 2);
        attempt.setStatus(AttemptStatus.FAILED_RETRYING);
        attempt.setResponseCode(503);
        attempt.setResponseBody("Service Unavailable");
        attempt.setLatencyMs(80L);
        attempt.setNextRetryAt(Instant.now().plusSeconds(120));

        // Act
        AttemptDetailDto result = underTest.toDetailDto(attempt);

        // Assert
        assertEquals(attempt.getId(), result.id());
        assertEquals("payment.completed", result.eventName());
        assertEquals(endpoint.getId(), result.endpointId());
        assertEquals("Production", result.endpointName());
        assertEquals(message.getId(), result.messageId());
        assertEquals(body, result.payload());
        assertEquals(2, result.attemptNo());
        assertEquals(AttemptStatus.FAILED_RETRYING, result.status());
        assertEquals(503, result.responseCode());
        assertEquals("Service Unavailable", result.responseBody());
        assertEquals(attempt.getLastError(), result.lastError());
        assertEquals(80L, result.latencyMs());
        assertEquals(attempt.getNextRetryAt(), result.nextRetryAt());
        assertEquals(attempt.getCreatedAt(), result.createdAt());
        assertEquals(attempt.getUpdatedAt(), result.updatedAt());
    }
}
