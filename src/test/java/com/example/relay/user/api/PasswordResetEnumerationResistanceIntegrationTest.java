package com.example.relay.user.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.endpoint.infrastructure.EndpointRepository;
import com.example.relay.environment.infrastructure.EnvironmentRepository;
import com.example.relay.event.infrastructure.EventRepository;
import com.example.relay.message.infrastructure.MessageRepository;
import com.example.relay.subscription.infrastructure.SubscriptionRepository;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class PasswordResetEnumerationResistanceIntegrationTest implements SharedPostgresContainer {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    // Every other table that can hold a row transitively referencing users(id), cleaned up here in
    // FK-safe (children-first) order before userRepository.deleteAll() below - not because this
    // test's own scenario touches any of them, but because this shared Postgres container is reused
    // across every @SpringBootTest class in the suite (see SharedPostgresContainer's javadoc), and a
    // blanket "delete every user" only succeeds once nothing anywhere still references one.
    @Autowired
    private AttemptRepository attemptRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private AppRepository appRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @BeforeEach
    void setUp() {
        attemptRepository.deleteAll();
        deliveryRepository.deleteAll();
        messageRepository.deleteAll();
        subscriptionRepository.deleteAll();
        endpointRepository.deleteAll();
        eventRepository.deleteAll();
        appRepository.deleteAll();
        environmentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();
        userRepository.save(new User("exists@example.com", "hash"));
    }

    /**
     * The "existing email" request in the test below goes through the real controller and PasswordResetService, which
     * really issues a token row for exists@example.com (undispatched, unused, unexpired - the RabbitMQ publish this
     * integration test setup doesn't consume). Left uncleaned, that row survives in this shared Postgres container and
     * matches other test classes' own (unscoped) queries over the whole password_reset_tokens table, e.g.
     * PasswordResetTokenRepositoryTest's dispatch-recovery finder.
     */
    @AfterEach
    void tearDown() {
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void requestResponse_isByteIdentical_forAnExistingAndANonExistentEmail() {
        ResponseEntity<String> existing = post("exists@example.com");
        ResponseEntity<String> nonExistent = post("does-not-exist@example.com");

        assertEquals(existing.getStatusCode(), nonExistent.getStatusCode());
        assertEquals(existing.getBody(), nonExistent.getBody());
        assertEquals(HttpStatus.OK, existing.getStatusCode());
    }

    private ResponseEntity<String> post(String email) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("/api/v1/auth/password-reset/request",
                new HttpEntity<>("{\"email\":\"" + email + "\"}", headers), String.class);
    }
}
