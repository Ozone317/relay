package com.example.relay.delivery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
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
 * request. A pre-insert DeliveryStatus read would land in that session's identity map. Because DeliveryStatus is
 * {@code @Immutable}, a later findById could silently return the pre-replay instance. Replay must perform its first
 * view read after the allocation transaction commits.
 *
 * <p>
 * Neither {@code DeliveryReplayServiceTest} (pure Mockito - no real Session, no OSIV) nor
 * {@code DeliveryReplayLifecycleIntegrationTest} (direct method calls under plain {@code @SpringBootTest} - each
 * repository call gets its own transaction-scoped EntityManager, no OSIV filter involved) can reproduce this: OSIV is a
 * servlet-filter behaviour that only exists when a request actually goes through the web layer. This test does that,
 * via {@code TestRestTemplate} against a real embedded server, which is the only way in this codebase to install the
 * real OSIV interceptor and observe the bug.
 */
@Tag("integration")
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
        // email_verification_tokens FKs to users (added in Task 1, after this test was written) - must
        // be cleared before userRepository.deleteAll() below, same as passwordResetTokenRepository above.
        emailVerificationTokenRepository.deleteAll();
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
            for (int number = 1; number <= 6; number++) {
                Attempt deadAttempt = new Attempt(app, message, endpoint, delivery, number);
                deadAttempt.setStatus(AttemptStatus.DEAD);
                attemptRepository.save(deadAttempt);
            }

            String token = jwtService.generateToken(user.getEmail(), user.getId(), true);
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);

            ResponseEntity<JsonNode> response = rest.exchange(
                    "/api/v1/environments/{environmentId}/apps/{appId}/deliveries/{deliveryId}/replay", HttpMethod.POST,
                    new HttpEntity<>(headers), JsonNode.class, env.getId(), app.getId(), delivery.getId());

            assertEquals(HttpStatus.CREATED, response.getStatusCode());
            JsonNode responseBody = response.getBody();
            // A real OSIV-bound request must expose the committed replay without a pre-insert view read or detach.
            assertEquals(7, responseBody.get("latestAttemptNo").asInt());
            assertEquals(7, responseBody.get("attemptCount").asInt());
            assertEquals("CREATED", responseBody.get("status").asText());
            Attempt created =
                    attemptRepository.findFirstByDeliveryIdOrderByAttemptNoDesc(delivery.getId()).orElseThrow();
            assertEquals(0, created.getExecutionGeneration());
            assertNull(created.getExecutionClaimedAt());

            ResponseEntity<JsonNode> conflict = rest.exchange(
                    "/api/v1/environments/{environmentId}/apps/{appId}/deliveries/{deliveryId}/replay", HttpMethod.POST,
                    new HttpEntity<>(headers), JsonNode.class, env.getId(), app.getId(), delivery.getId());
            assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
            assertEquals(7,
                    attemptRepository
                            .findByDeliveryId(delivery.getId(), org.springframework.data.domain.Pageable.unpaged())
                            .getTotalElements());
        } finally {
            clearDatabase();
        }
    }
}
