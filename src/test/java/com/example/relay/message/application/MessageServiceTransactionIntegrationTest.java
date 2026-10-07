package com.example.relay.message.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.endpoint.infrastructure.EndpointRepository;
import com.example.relay.environment.domain.Environment;
import com.example.relay.environment.infrastructure.EnvironmentRepository;
import com.example.relay.event.domain.Event;
import com.example.relay.event.infrastructure.EventRepository;
import com.example.relay.message.api.MessageIdempotencyKey;
import com.example.relay.message.api.dto.MessageCreateDto;
import com.example.relay.message.domain.Message;
import com.example.relay.message.exception.NoActiveSubscribersException;
import com.example.relay.message.infrastructure.MessageRepository;
import com.example.relay.subscription.domain.Subscription;
import com.example.relay.subscription.infrastructure.SubscriptionRepository;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Tag("integration")
@SpringBootTest
class MessageServiceTransactionIntegrationTest implements SharedPostgresContainer {

    @Autowired private MessageService underTest;
    @Autowired private MessageRepository messageRepository;
    @MockitoSpyBean private AttemptRepository attemptRepository;
    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private EnvironmentRepository environmentRepository;
    @Autowired private AppRepository appRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EndpointRepository endpointRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired private EmailVerificationTokenRepository emailVerificationTokenRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        clearMessageGraph();
    }

    @Test
    void keyedSuccessCommitsIdentityMessageDeliveriesAndAttemptsCoherently() throws Exception {
        Fixture fixture = persistFixture("keyed-success@mail.com", true);
        MessageCreateDto request = request(fixture.event());
        MessageIdempotencyKey key = new MessageIdempotencyKey("success-key");

        Message result = underTest.create(request, Optional.of(key), fixture.app().getId(), fixture.env().getId(),
                fixture.user().getId()).message();

        assertCounts(1, 1, 1, 1);
        assertKeyedCoherence(fixture, key, result, 1);
    }

    @Test
    void keyedFanoutToThreeSubscribersStartsEveryAttemptInCreatedGenerationZero() throws Exception {
        Fixture fixture = persistFixture("three-subscriber@mail.com", true);
        for (int i = 2; i <= 3; i++) {
            Endpoint endpoint = endpointRepository.save(new Endpoint("endpoint-" + i,
                    "https://example.com/webhook/" + i, "secret-" + i, fixture.app()));
            subscriptionRepository.save(new Subscription(fixture.app(), fixture.event(), endpoint));
        }
        MessageIdempotencyKey key = new MessageIdempotencyKey("three-subscriber-key");

        Message accepted = underTest.create(request(fixture.event()), Optional.of(key), fixture.app().getId(),
                fixture.env().getId(), fixture.user().getId()).message();

        assertCounts(1, 1, 3, 3);
        assertKeyedCoherence(fixture, key, accepted, 3);
    }

    @Test
    void attemptFailureRollsBackAllRowsAndRetryWithSameKeySucceeds() throws Exception {
        Fixture fixture = persistFixture("retry@mail.com", true);
        MessageCreateDto request = request(fixture.event());
        MessageIdempotencyKey key = new MessageIdempotencyKey("retry-key");
        doThrow(new RuntimeException("Attempt persistence failed")).when(attemptRepository).saveAll(anyList());

        assertThrows(RuntimeException.class, () -> underTest.create(request, Optional.of(key), fixture.app().getId(),
                fixture.env().getId(), fixture.user().getId()));
        assertCounts(0, 0, 0, 0);

        reset(attemptRepository);
        Message retry = underTest.create(request, Optional.of(key), fixture.app().getId(), fixture.env().getId(),
                fixture.user().getId()).message();
        assertCounts(1, 1, 1, 1);
        assertKeyedCoherence(fixture, key, retry, 1);
    }

    @Test
    void noSubscriberKeyedRequestRollsBackIdentityAcquisition() throws Exception {
        Fixture fixture = persistFixture("no-subscribers@mail.com", false);
        MessageIdempotencyKey key = new MessageIdempotencyKey("no-subscriber-key");

        assertThrows(NoActiveSubscribersException.class, () -> underTest.create(request(fixture.event()), Optional.of(key),
                fixture.app().getId(), fixture.env().getId(), fixture.user().getId()));

        assertCounts(0, 0, 0, 0);
    }

    @Test
    void committedReplayReturnsOriginalMessageWithoutChangingGraphCounts() throws Exception {
        Fixture fixture = persistFixture("replay@mail.com", true);
        MessageCreateDto request = request(fixture.event());
        MessageIdempotencyKey key = new MessageIdempotencyKey("replay-key");
        Message first = underTest.create(request, Optional.of(key), fixture.app().getId(), fixture.env().getId(),
                fixture.user().getId()).message();
        Instant originalCreatedAt = first.getCreatedAt();
        long[] countsBeforeReplay = counts();

        Message replay = underTest.create(request, Optional.of(key), fixture.app().getId(), fixture.env().getId(),
                fixture.user().getId()).message();

        assertEquals(first.getId(), replay.getId());
        assertEquals(originalCreatedAt, replay.getCreatedAt());
        assertEquals(countsBeforeReplay[0], counts()[0]);
        assertEquals(countsBeforeReplay[1], counts()[1]);
        assertEquals(countsBeforeReplay[2], counts()[2]);
        assertEquals(countsBeforeReplay[3], counts()[3]);
        assertKeyedCoherence(fixture, key, replay, 1);
    }

    @Test
    void keylessIdenticalSubmissionsCreateIndependentGraphsWithoutIdentityRows() throws Exception {
        Fixture fixture = persistFixture("keyless@mail.com", true);
        MessageCreateDto request = request(fixture.event());

        Message first = underTest.create(request, Optional.empty(), fixture.app().getId(), fixture.env().getId(),
                fixture.user().getId()).message();
        Message second = underTest.create(request, Optional.empty(), fixture.app().getId(), fixture.env().getId(),
                fixture.user().getId()).message();

        assertNotEquals(first.getId(), second.getId());
        assertCounts(0, 2, 2, 2);
    }

    private Fixture persistFixture(String email, boolean withSubscriber) {
        User user = userRepository.save(new User(email, "passwordHash"));
        Environment env = environmentRepository.save(new Environment("Env 1", "Desc 1", user));
        App app = appRepository.save(new App("App 1", env));
        Event event = eventRepository.save(new Event("payment.created", app));
        if (withSubscriber) {
            Endpoint endpoint = endpointRepository.save(
                    new Endpoint("endpoint", "https://example.com/webhook", "secret", app));
            subscriptionRepository.save(new Subscription(app, event, endpoint));
        }
        return new Fixture(user, env, app, event);
    }

    private MessageCreateDto request(Event event) throws Exception {
        return new MessageCreateDto(event.getId(), objectMapper.readTree("{\"amount\":1}"));
    }

    private void assertKeyedCoherence(Fixture fixture, MessageIdempotencyKey key, Message message,
            int subscriberCount) {
        Long coherent = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM message_idempotency i
                JOIN messages m ON m.id = i.message_id
                JOIN apps a ON a.id = m.app_id
                JOIN environments e ON e.id = a.environment_id
                WHERE i.user_id = e.user_id
                  AND i.app_id = m.app_id
                  AND i.user_id = ?
                  AND i.app_id = ?
                  AND i.idempotency_key = ?
                  AND i.message_id = ?
                """, Long.class, fixture.user().getId(), fixture.app().getId(), key.value(), message.getId());
        assertEquals(1L, coherent);
        assertEquals((long) subscriberCount, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM deliveries WHERE message_id = ?", Long.class, message.getId()));
        assertEquals((long) subscriberCount, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attempts WHERE message_id = ?", Long.class, message.getId()));
        Long invalidAttemptStateCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM attempts
                WHERE message_id = ?
                  AND (attempt_no <> 1 OR status <> 'CREATED' OR execution_generation <> 0
                       OR execution_claimed_at IS NOT NULL)
                """, Long.class, message.getId());
        assertEquals(0L, invalidAttemptStateCount);
        Message scoped = messageRepository.findByIdAndAppIdAndEnvironmentIdAndUserId(message.getId(),
                fixture.app().getId(), fixture.env().getId(), fixture.user().getId()).orElseThrow();
        assertEquals(message.getId(), scoped.getId());

        Instant acceptedAt = jdbcTemplate.queryForObject("SELECT accepted_at FROM message_idempotency "
                + "WHERE user_id = ? AND app_id = ? AND idempotency_key = ?", (rs, rowNum) -> rs.getTimestamp(1).toInstant(),
                fixture.user().getId(), fixture.app().getId(), key.value());
        assertEquals(scoped.getCreatedAt(), acceptedAt);
    }

    private void assertCounts(long identities, long messages, long deliveries, long attempts) {
        long[] actual = counts();
        assertEquals(identities, actual[0]);
        assertEquals(messages, actual[1]);
        assertEquals(deliveries, actual[2]);
        assertEquals(attempts, actual[3]);
    }

    private long[] counts() {
        return new long[] {
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM message_idempotency", Long.class),
            messageRepository.count(),
            deliveryRepository.count(),
            attemptRepository.count()
        };
    }

    private void clearMessageGraph() {
        jdbcTemplate.update("DELETE FROM attempts");
        jdbcTemplate.update("DELETE FROM deliveries");
        jdbcTemplate.update("DELETE FROM message_idempotency");
        jdbcTemplate.update("DELETE FROM messages");
    }

    @AfterEach
    void cleanUp() {
        subscriptionRepository.deleteAll();
        clearMessageGraph();
        endpointRepository.deleteAll();
        eventRepository.deleteAll();
        appRepository.deleteAll();
        environmentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    private record Fixture(User user, Environment env, App app, Event event) { }
}
