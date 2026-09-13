package com.example.relay.delivery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.common.security.JwtService;
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
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Regression test for the Open-Session-In-View stale-read bug in {@link DeliveryReplayService#replay}: with OSIV on
 * (the project default - spring.jpa.open-in-view is never overridden), one Hibernate Session is bound to the whole HTTP
 * request. The DeliveryStatus row loaded early in replay() lands in that session's identity map, and because
 * DeliveryStatus is {@code @Immutable}, a later findById for the "fresh" post-replay row silently returned the same
 * pre-replay instance without issuing any SQL.
 *
 * <p>
 * Neither {@code DeliveryReplayServiceTest} (pure Mockito - no real Session, no OSIV) nor
 * {@code DeliveryReplayLifecycleIntegrationTest} (direct method calls under plain {@code @SpringBootTest} - each
 * repository call gets its own transaction-scoped EntityManager, no OSIV filter involved) can reproduce this: OSIV is a
 * servlet-filter behaviour that only exists when a request actually goes through the web layer. This test does that,
 * via {@code TestRestTemplate} against a real embedded server, which is the only way in this codebase to install the
 * real OSIV interceptor and observe the bug.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeliveryReplayHttpIntegrationTest implements SharedPostgresContainer {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private AttemptRepository attemptRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

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

    private void clearDatabase() {
        attemptRepository.deleteAll();
        deliveryRepository.deleteAll();
        messageRepository.deleteAll();
        endpointRepository.deleteAll();
        eventRepository.deleteAll();
        appRepository.deleteAll();
        environmentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        // password_reset_tokens FKs to users (added in Task 4, after this test was written) - must be
        // cleared before userRepository.deleteAll() below, same as refreshTokenRepository above.
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void replay_viaRealHttpRequest_returnsTheFreshPostReplayStatus_notTheStalePreReplaySnapshot() throws Exception {
        clearDatabase();
        try {
            User user = userRepository.save(new User("replay-http-" + UUID.randomUUID() + "@mail.com", "hash"));
            Environment env = environmentRepository.save(new Environment("Env 1", "Desc 1", user));
            App app = appRepository.save(new App("App 1", env));
            Event event = eventRepository.save(new Event("payment.completed", app));
            Endpoint endpoint =
                    endpointRepository.save(new Endpoint("EP 1", "https://example.com/webhook", "whsec_1", app));
            ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
            Message message = messageRepository.save(new Message(app, event, body));

            Delivery delivery = deliveryRepository.save(new Delivery(app, message, endpoint));
            Attempt deadAttempt = new Attempt(app, message, endpoint, delivery, 6);
            deadAttempt.setStatus(AttemptStatus.DEAD);
            attemptRepository.save(deadAttempt);

            String token = jwtService.generateToken(user.getEmail(), user.getId());
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);

            ResponseEntity<JsonNode> response = rest.exchange(
                    "/api/v1/environments/{environmentId}/apps/{appId}/deliveries/{deliveryId}/replay", HttpMethod.POST,
                    new HttpEntity<>(headers), JsonNode.class, env.getId(), app.getId(), delivery.getId());

            assertEquals(HttpStatus.CREATED, response.getStatusCode());
            JsonNode responseBody = response.getBody();
            // THE POINT OF THE TEST: before the entityManager.detach(current) fix, this endpoint
            // returned the stale, pre-replay snapshot (latestAttemptNo=6, attemptCount=1) because
            // the DeliveryStatus loaded earlier in replay() stayed in the OSIV session's identity
            // map across attemptService.createReplay's commit.
            assertEquals(7, responseBody.get("latestAttemptNo").asInt());
            assertEquals(2, responseBody.get("attemptCount").asInt());
        } finally {
            clearDatabase();
        }
    }
}
