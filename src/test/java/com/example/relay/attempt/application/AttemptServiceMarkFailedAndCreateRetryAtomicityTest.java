package com.example.relay.attempt.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptAllocationRepository;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.endpoint.infrastructure.EndpointRepository;
import com.example.relay.environment.domain.Environment;
import com.example.relay.environment.infrastructure.EnvironmentRepository;
import com.example.relay.event.domain.Event;
import com.example.relay.event.infrastructure.EventRepository;
import com.example.relay.message.domain.Message;
import com.example.relay.message.infrastructure.MessageRepository;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("integration")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AttemptServiceMarkFailedAndCreateRetryAtomicityTest implements SharedPostgresContainer {

    @Autowired
    private AttemptService attemptService;

    @MockitoSpyBean
    private AttemptRepository attemptRepository;

    @MockitoSpyBean
    private AttemptAllocationRepository allocationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

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

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private Endpoint endpoint;
    private Message message;

    @BeforeEach
    void setUp() {
        attemptRepository.deleteAll();
        deliveryRepository.deleteAll();
        messageRepository.deleteAll();
        endpointRepository.deleteAll();
        eventRepository.deleteAll();
        appRepository.deleteAll();
        environmentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
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
    void retryInsertFailure_rollsBackParentAndConsumesNoNumber() {
        // Arrange - a claimed (IN_FLIGHT) attempt, matching what DeliveryWorker always passes in
        Delivery delivery = deliveryRepository.save(new Delivery(endpoint.getApp(), message, endpoint));
        UUID attemptId = attemptRepository.save(new Attempt(endpoint.getApp(), message, endpoint, delivery, 1)).getId();
        AttemptExecution execution = attemptService.claim(attemptId).orElseThrow();

        AtomicInteger allocatedNumber = new AtomicInteger();
        AttemptAllocationRepository allocationSpy = AopTestUtils.getUltimateTargetObject(allocationRepository);
        doAnswer(invocation -> {
            assertEquals(true, TransactionSynchronizationManager.isActualTransactionActive());
            int number = (int) invocation.callRealMethod();
            allocatedNumber.set(number);
            return number;
        }).when(allocationSpy).nextAttemptNoUnderDeliveryLock(any());
        doThrow(new RuntimeException("simulated crash during retry insertion")).when(attemptRepository)
                .save(argThat(a -> a != null && a.getStatus() == AttemptStatus.SCHEDULED));

        Instant dueAt = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        // Act & Assert
        assertThrows(RuntimeException.class,
                () -> attemptService.markFailedAndCreateRetry(execution, dueAt, 500, "internal error", null, 120L));
        assertEquals(2, allocatedNumber.get(), "child insertion follows current-max allocation");

        // Assert - the whole transaction rolled back: the parent is still IN_FLIGHT (its
        // pre-call state), NOT FAILED_RETRYING, and no retry row was ever persisted. Before this
        // method existed as a single transaction, markFailed's write would have survived a crash
        // exactly like this one - this is the regression this method exists to prevent.
        Attempt reloaded = attemptRepository.findById(attemptId).orElseThrow();
        assertEquals(AttemptStatus.IN_FLIGHT, reloaded.getStatus());
        var persistedParent = jdbc.queryForMap(
                "SELECT status, execution_generation, execution_claimed_at FROM attempts WHERE id = ?", attemptId);
        assertEquals("IN_FLIGHT", persistedParent.get("status"));
        assertEquals(1L, ((Number) persistedParent.get("execution_generation")).longValue());
        assertNotNull(persistedParent.get("execution_claimed_at"));
        assertEquals(1, attemptRepository.findAll().size());
        assertEquals(List.of(1),
                jdbc.queryForList("SELECT attempt_no FROM attempts WHERE delivery_id = ? ORDER BY attempt_no",
                        Integer.class, delivery.getId()));

        reset(attemptRepository);
        assertEquals(AttemptMutationOutcome.APPLIED,
                attemptService.markFailedAndCreateRetry(execution, dueAt, 500, "internal error", null, 120L));
        assertEquals(List.of(1, 2),
                jdbc.queryForList("SELECT attempt_no FROM attempts WHERE delivery_id = ? ORDER BY attempt_no",
                        Integer.class, delivery.getId()));
        assertEquals("FAILED_RETRYING",
                jdbc.queryForObject("SELECT status FROM attempts WHERE id = ?", String.class, attemptId));
        var child = jdbc.queryForMap("SELECT status, execution_generation, execution_claimed_at, next_retry_at "
                + "FROM attempts WHERE delivery_id = ? AND attempt_no = 2", delivery.getId());
        assertEquals("SCHEDULED", child.get("status"));
        assertEquals(0L, ((Number) child.get("execution_generation")).longValue());
        assertNull(child.get("execution_claimed_at"));
        assertEquals(dueAt,
                jdbc.queryForObject("SELECT next_retry_at FROM attempts WHERE delivery_id = ? AND attempt_no = 2",
                        Instant.class, delivery.getId()));
    }
}
