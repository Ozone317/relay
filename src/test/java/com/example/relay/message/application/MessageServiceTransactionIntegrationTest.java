package com.example.relay.message.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

import com.example.relay.app.domain.App;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.endpoint.infrastructure.EndpointRepository;
import com.example.relay.environment.domain.Environment;
import com.example.relay.environment.infrastructure.EnvironmentRepository;
import com.example.relay.event.domain.Event;
import com.example.relay.event.infrastructure.EventRepository;
import com.example.relay.message.api.dto.MessageCreateDto;
import com.example.relay.message.infrastructure.MessageRepository;
import com.example.relay.subscription.domain.Subscription;
import com.example.relay.subscription.infrastructure.SubscriptionRepository;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
public class MessageServiceTransactionIntegrationTest implements SharedPostgresContainer {

    @Autowired
    private MessageService underTest;

    @Autowired
    private MessageRepository messageRepository;

    @MockitoBean
    private AttemptRepository attemptRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private AppRepository appRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        // attemptRepository is @MockitoBean here, so it cannot clear real attempt rows left by
        // other test classes - and those rows FK-reference messages. Raw SQL first, then messages.
        // deliveries must go before messages too - it FK-references messages directly, and
        // AttemptService now creates one real Delivery row per (message, endpoint) pair.
        jdbcTemplate.update("DELETE FROM attempts");
        deliveryRepository.deleteAll();
        messageRepository.deleteAll();
    }

    @Test
    void create_shouldRollbackMessage_whenAttemptCreationFails() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.created", app);
        Endpoint endpoint = new Endpoint("endpoint 1", "https://example.com/webhook", "whsec_some_scret", app);
        Subscription subscription = new Subscription(app, event, endpoint);
        MessageCreateDto request = new MessageCreateDto(event.getId(), objectMapper.readTree("""
                    {
                        "message": "Hello",
                        "count": 42,
                        "active": true
                    }
                """));

        // Persist requisite data
        userRepository.save(user);
        environmentRepository.save(env);
        appRepository.save(app);
        eventRepository.save(event);
        endpointRepository.save(endpoint);
        subscriptionRepository.save(subscription);

        // Make attempt persistence fail
        doThrow(new RuntimeException("Attempt persistence failed")).when(attemptRepository).saveAll(anyList());

        // Act
        assertThrows(RuntimeException.class, () -> underTest.create(request, app.getId(), env.getId(), user.getId()));

        // Assert
        assertEquals(0, messageRepository.count());
    }

    @AfterEach
    void cleanUp() {
        // Explicit cleanup to remove this test's created fixtures. This test is not @Transactional,
        // so manual cleanup ensures no data persists to affect subsequent tests.
        subscriptionRepository.deleteAll();
        deliveryRepository.deleteAll();
        messageRepository.deleteAll();
        endpointRepository.deleteAll();
        eventRepository.deleteAll();
        appRepository.deleteAll();
        environmentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }
}
