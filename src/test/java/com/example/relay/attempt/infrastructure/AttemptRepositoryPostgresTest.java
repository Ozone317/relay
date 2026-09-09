package com.example.relay.attempt.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.relay.app.domain.App;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.message.domain.Message;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import com.example.relay.support.SharedPostgresContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * The same repository queries as {@link AttemptRepositoryTest}, kept as its own regression test rather than folded
 * back in.
 *
 * <p>
 * Before the project moved to PostgreSQL-only testing, every other repository test ran on H2, which was far more
 * forgiving than Postgres about untyped bind parameters. That divergence once hid a total failure of the attempts
 * list endpoint: `findByAppIdAndFilters` threw `SQLState 42P18 - could not determine data type of parameter $6` on
 * every single call against Postgres, while the H2 suite stayed green — Postgres determines parameter types at
 * PREPARE time from the SQL text alone, so a parameter whose only appearance is `? IS NULL` has no inferable type.
 * The fix is now covered here directly, and this class stays as the named regression test for that specific bug.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AttemptRepositoryPostgresTest implements SharedPostgresContainer {

    @Autowired
    private AttemptRepository underTest;

    @Autowired
    private TestEntityManager testEntityManager;

    private App app;
    private Endpoint endpoint;
    private Message message;

    @BeforeEach
    void setUp() throws Exception {
        User user = new User("pg-" + UUID.randomUUID() + "@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        message = new Message(app, event, new ObjectMapper().readTree("{\"name\": \"hello\"}"));

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
    }

    private Attempt persistAttempt(int attemptNo) {
        Delivery delivery = new Delivery(app, message, endpoint);
        testEntityManager.persistAndFlush(delivery);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, attemptNo);
        testEntityManager.persistAndFlush(attempt);
        return attempt;
    }

    private Attempt persistAttempt(int attemptNo, Endpoint targetEndpoint) {
        Delivery delivery = new Delivery(app, message, targetEndpoint);
        testEntityManager.persistAndFlush(delivery);
        Attempt attempt = new Attempt(app, message, targetEndpoint, delivery, attemptNo);
        testEntityManager.persistAndFlush(attempt);
        return attempt;
    }

    private Attempt persistAttemptWithMessage(int attemptNo, Message targetMessage) {
        Delivery delivery = new Delivery(app, targetMessage, endpoint);
        testEntityManager.persistAndFlush(delivery);
        Attempt attempt = new Attempt(app, targetMessage, endpoint, delivery, attemptNo);
        testEntityManager.persistAndFlush(attempt);
        return attempt;
    }

    @Test
    void findByAppIdAndFilters_returnsEveryAttempt_whenNoFilterIsSupplied() {
        // Distinct endpoints: idx_attempts_one_active_per_message_endpoint allows only one active
        // (CREATED/IN_FLIGHT/SCHEDULED) row per (message_id, endpoint_id) pair, and both attempts
        // here default to CREATED. Endpoint identity is irrelevant to this test.
        Endpoint endpoint2 = new Endpoint("testing2", "https://example.com/2", "whsec_2", app);
        testEntityManager.persistAndFlush(endpoint2);
        persistAttempt(1);
        persistAttempt(2, endpoint2);

        // The regression: on Postgres this threw 42P18 before the query was rewritten, so the
        // delivery-history list endpoint returned a masked 401 for every caller.
        Page<Attempt> result = underTest.findByAppIdAndFilters(app.getId(), null, null, null, null,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertEquals(2, result.getTotalElements());
    }

    @Test
    void findByAppIdAndFilters_appliesEveryFilterTogether() throws Exception {
        // Distinct messages, same endpoint: both rows must match endpoint.getId() below, so the
        // pair that idx_attempts_one_active_per_message_endpoint keys on (message_id, endpoint_id)
        // must differ by message instead. Both attempts here default to CREATED.
        Message message2 = new Message(app, message.getEvent(), new ObjectMapper().readTree("{\"name\": \"hello2\"}"));
        testEntityManager.persistAndFlush(message2);
        Attempt matching = persistAttempt(1);
        persistAttemptWithMessage(2, message2);

        Page<Attempt> result = underTest.findByAppIdAndFilters(app.getId(), endpoint.getId(), AttemptStatus.CREATED,
                Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS),
                PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "attemptNo")));

        assertEquals(2, result.getTotalElements());
        assertEquals(matching.getId(), result.getContent().get(0).getId());
    }

    @Test
    void findByAppIdAndFilters_filtersByStatusAlone() {
        persistAttempt(1);

        assertEquals(1, underTest
                .findByAppIdAndFilters(app.getId(), null, AttemptStatus.CREATED, null, null, PageRequest.of(0, 20))
                .getTotalElements());
        assertEquals(0,
                underTest
                        .findByAppIdAndFilters(app.getId(), null, AttemptStatus.DEAD, null, null, PageRequest.of(0, 20))
                        .getTotalElements());
    }

    @Test
    void findByAppIdAndFilters_filtersByEndpointAlone() {
        persistAttempt(1);

        assertEquals(1,
                underTest.findByAppIdAndFilters(app.getId(), endpoint.getId(), null, null, null, PageRequest.of(0, 20))
                        .getTotalElements());
        assertEquals(0,
                underTest.findByAppIdAndFilters(app.getId(), UUID.randomUUID(), null, null, null, PageRequest.of(0, 20))
                        .getTotalElements());
    }

    @Test
    void findByAppIdAndFilters_filtersByDateRangeAlone() {
        persistAttempt(1);

        assertEquals(1, underTest.findByAppIdAndFilters(app.getId(), null, null,
                Instant.now().minus(1, ChronoUnit.DAYS), null, PageRequest.of(0, 20)).getTotalElements());
        assertEquals(0, underTest.findByAppIdAndFilters(app.getId(), null, null, Instant.now().plus(1, ChronoUnit.DAYS),
                null, PageRequest.of(0, 20)).getTotalElements());
    }
}
