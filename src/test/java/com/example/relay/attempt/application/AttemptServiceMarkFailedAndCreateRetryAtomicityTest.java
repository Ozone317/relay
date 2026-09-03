package com.example.relay.attempt.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.endpoint.infrastructure.EndpointRepository;
import com.example.relay.environment.domain.Environment;
import com.example.relay.environment.infrastructure.EnvironmentRepository;
import com.example.relay.event.domain.Event;
import com.example.relay.event.infrastructure.EventRepository;
import com.example.relay.message.domain.Message;
import com.example.relay.message.infrastructure.MessageRepository;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AttemptServiceMarkFailedAndCreateRetryAtomicityTest {

    @Autowired
    private AttemptService attemptService;

    @MockitoSpyBean
    private AttemptRepository attemptRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private AppRepository appRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private MessageRepository messageRepository;

    private Endpoint endpoint;
    private Message message;

    @BeforeEach
    void setUp() {
        attemptRepository.deleteAll();
        messageRepository.deleteAll();
        endpointRepository.deleteAll();
        eventRepository.deleteAll();
        appRepository.deleteAll();
        environmentRepository.deleteAll();
        userRepository.deleteAll();

        User user = userRepository.save(new User("test" + UUID.randomUUID() + "@mail.com", "hash"));
        Environment env = environmentRepository.save(new Environment("Env 1", "Desc 1", user));
        App app = appRepository.save(new App("App 1", env));
        Event event = eventRepository.save(new Event("payment.completed", app));
        endpoint = endpointRepository.save(new Endpoint("EP 1", "https://example.com/webhook", "whsec_1", app));
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        message = messageRepository.save(new Message(app, event, body));
    }

    @Test
    void aFailureWhileCreatingTheRetry_rollsBackTheMarkFailedWriteToo() {
        // Arrange - a claimed (IN_FLIGHT) attempt, matching what DeliveryWorker always passes in
        UUID attemptId = attemptRepository.save(new Attempt(endpoint.getApp(), message, endpoint, 1)).getId();
        attemptService.claim(attemptId, Instant.now());

        // The retry row is the only save() call that sets status=SCHEDULED - target only that
        // one, simulating a crash specifically during the createRetry half of the merged method.
        doThrow(new RuntimeException("simulated crash during createRetry"))
                .when(attemptRepository)
                .save(argThat(a -> a != null && a.getStatus() == AttemptStatus.SCHEDULED));

        Instant dueAt = Instant.now().plusSeconds(30);
        Attempt attempt = attemptRepository.findById(attemptId).orElseThrow();

        // Act & Assert
        assertThrows(RuntimeException.class, () -> attemptService.markFailedAndCreateRetry(
                attempt, dueAt, 500, "internal error", null, 120L));

        // Assert - the whole transaction rolled back: the parent is still IN_FLIGHT (its
        // pre-call state), NOT FAILED_RETRYING, and no retry row was ever persisted. Before this
        // method existed as a single transaction, markFailed's write would have survived a crash
        // exactly like this one - this is the regression this method exists to prevent.
        Attempt reloaded = attemptRepository.findById(attemptId).orElseThrow();
        assertEquals(AttemptStatus.IN_FLIGHT, reloaded.getStatus());
        assertEquals(1, attemptRepository.findAll().size());
    }
}
