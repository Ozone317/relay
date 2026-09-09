package com.example.relay.attempt.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
import com.example.relay.support.SharedPostgresContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public class AttemptRepositoryTest implements SharedPostgresContainer {

    @Autowired
    private AttemptRepository underTest;

    private ObjectMapper objectMapper;

    @Autowired
    private TestEntityManager testEntityManager;

    @Test
    void claim_returns1_whenRowMatchesTheConditions() throws Exception {
        // Arrange
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        // Act
        int rowsAffected = underTest.claim(attempt.getId(), Instant.now());

        // Assert
        assertEquals(1, rowsAffected);

        Attempt fetchedAttempt = underTest.findById(attempt.getId()).get();
        assertEquals(AttemptStatus.IN_FLIGHT, fetchedAttempt.getStatus());
    }

    @Test
    void claim_returns0_whenRowMatchesButConditionsDont() throws Exception {
        // Arrange
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        underTest.claim(attempt.getId(), Instant.now());

        // Act (claiming the already claimed row)
        int rowsAffected = underTest.claim(attempt.getId(), Instant.now());

        // Assert
        assertEquals(0, rowsAffected);
    }

    @Test
    void findByStatusAndUpdatedAtBefore_returnsOnlyStaleMatchingRows() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));

        // Each gets its own endpoint: idx_attempts_one_active_per_message_endpoint allows only one
        // active (CREATED/IN_FLIGHT/SCHEDULED) row per (message_id, endpoint_id) pair, and endpoint
        // identity is irrelevant to what this test checks.
        Endpoint endpointFresh = new Endpoint("testing-fresh", "https://example.com/fresh", "whsec_fresh", app);
        Endpoint endpointStaleWrongStatus =
                new Endpoint("testing-swrong", "https://example.com/swrong", "whsec_swrong", app);
        Delivery staleDelivery = new Delivery(app, message, endpoint);
        Delivery freshDelivery = new Delivery(app, message, endpointFresh);
        Delivery staleButWrongStatusDelivery = new Delivery(app, message, endpointStaleWrongStatus);
        Attempt stale = new Attempt(app, message, endpoint, staleDelivery, 1);
        Attempt fresh = new Attempt(app, message, endpointFresh, freshDelivery, 1);
        Attempt staleButWrongStatus = new Attempt(app, message, endpointStaleWrongStatus, staleButWrongStatusDelivery, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(endpointFresh);
        testEntityManager.persistAndFlush(endpointStaleWrongStatus);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(staleDelivery);
        testEntityManager.persistAndFlush(freshDelivery);
        testEntityManager.persistAndFlush(staleButWrongStatusDelivery);
        testEntityManager.persistAndFlush(stale);
        testEntityManager.persistAndFlush(fresh);
        testEntityManager.persistAndFlush(staleButWrongStatus);

        Instant longAgo = Instant.now().minusSeconds(3600);
        backdateUpdatedAt(stale.getId(), longAgo);
        backdateUpdatedAt(staleButWrongStatus.getId(), longAgo);
        underTest.claim(staleButWrongStatus.getId(), Instant.now()); // flips it to IN_FLIGHT

        Instant threshold = Instant.now().minusSeconds(60);

        // Act
        List<Attempt> result = underTest.findByStatusAndUpdatedAtBefore(
                AttemptStatus.CREATED, threshold, Limit.of(100));

        // Assert
        assertEquals(1, result.size());
        assertEquals(stale.getId(), result.get(0).getId());
        assertTrue(result.stream().noneMatch(a -> a.getId().equals(fresh.getId()))); // Fresh row is not returned
    }

    @Test
    void findByStatusAndUpdatedAtBefore_respectsLimit() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);

        Instant longAgo = Instant.now().minusSeconds(3600);
        for (int i = 0; i < 3; i++) {
            // Distinct endpoint per iteration: idx_attempts_one_active_per_message_endpoint allows
            // only one active row per (message_id, endpoint_id) pair.
            Endpoint iterationEndpoint =
                    new Endpoint("testing-" + i, "https://example.com/" + i, "whsec_" + i, app);
            testEntityManager.persistAndFlush(iterationEndpoint);
            Delivery iterationDelivery = new Delivery(app, message, iterationEndpoint);
            testEntityManager.persistAndFlush(iterationDelivery);
            Attempt attempt = new Attempt(app, message, iterationEndpoint, iterationDelivery, 1);
            testEntityManager.persistAndFlush(attempt);
            backdateUpdatedAt(attempt.getId(), longAgo);
        }

        // Act
        List<Attempt> result = underTest.findByStatusAndUpdatedAtBefore(
                AttemptStatus.CREATED, Instant.now().minusSeconds(60), Limit.of(2));

        // Assert
        assertEquals(2, result.size());
    }

    @Test
    void resetStuck_returns1AndFlipsStatus_whenInFlightAndStale() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        underTest.claim(attempt.getId(), Instant.now()); // CREATED -> IN_FLIGHT
        backdateUpdatedAt(attempt.getId(), Instant.now().minusSeconds(3600));

        // Act
        int rowsAffected = underTest.resetStuck(attempt.getId(), Instant.now().minusSeconds(60), Instant.now());

        // Assert
        assertEquals(1, rowsAffected);
        assertEquals(AttemptStatus.CREATED, underTest.findById(attempt.getId()).get().getStatus());
    }

    @Test
    void resetStuck_returns0_whenNotStaleEnough() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        underTest.claim(attempt.getId(), Instant.now()); // updated_at is "now", not stale

        // Act
        int rowsAffected = underTest.resetStuck(attempt.getId(), Instant.now().minusSeconds(60), Instant.now());

        // Assert
        assertEquals(0, rowsAffected);
        assertEquals(AttemptStatus.IN_FLIGHT, underTest.findById(attempt.getId()).get().getStatus());
    }

    @Test
    void resetStuck_returns0_whenStatusIsNotInFlight() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 1); // stays CREATED

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        backdateUpdatedAt(attempt.getId(), Instant.now().minusSeconds(3600));

        // Act
        int rowsAffected = underTest.resetStuck(attempt.getId(), Instant.now().minusSeconds(60), Instant.now());

        // Assert
        assertEquals(0, rowsAffected);
        assertEquals(AttemptStatus.CREATED, underTest.findById(attempt.getId()).get().getStatus());
    }

    @Test
    void claim_advancesUpdatedAt() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        // Simulate a retry that sat in a wait tier for hours before being claimed.
        backdateUpdatedAt(attempt.getId(), Instant.now().minusSeconds(21_600));

        // Act
        Instant now = Instant.now();
        underTest.claim(attempt.getId(), now);

        // Assert
        Attempt reloaded = underTest.findById(attempt.getId()).get();
        assertEquals(now.truncatedTo(ChronoUnit.MILLIS), reloaded.getUpdatedAt().truncatedTo(ChronoUnit.MILLIS),
                "claim() should stamp updated_at to the bound now parameter, not leave the old value");
    }

    @Test
    void resetStuck_advancesUpdatedAt() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        underTest.claim(attempt.getId(), Instant.now());
        backdateUpdatedAt(attempt.getId(), Instant.now().minusSeconds(3600));

        // Act
        Instant now = Instant.now();
        underTest.resetStuck(attempt.getId(), Instant.now().minusSeconds(60), now);

        // Assert
        Attempt reloaded = underTest.findById(attempt.getId()).get();
        assertEquals(now.truncatedTo(ChronoUnit.MILLIS), reloaded.getUpdatedAt().truncatedTo(ChronoUnit.MILLIS),
                "resetStuck() should stamp updated_at to the bound now parameter, not leave the old value");
    }

    @Test
    void claim_returns1_whenRowIsScheduled() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 2);
        attempt.setStatus(AttemptStatus.SCHEDULED);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        // Act
        int rowsAffected = underTest.claim(attempt.getId(), Instant.now());

        // Assert
        assertEquals(1, rowsAffected);
        assertEquals(AttemptStatus.IN_FLIGHT, underTest.findById(attempt.getId()).get().getStatus());
    }

    @Test
    void findByStatusAndNextRetryAtBefore_returnsOnlyOverdueScheduledRows() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));

        // Distinct endpoints: idx_attempts_one_active_per_message_endpoint allows only one active
        // (CREATED/IN_FLIGHT/SCHEDULED) row per (message_id, endpoint_id) pair, and both of these
        // are SCHEDULED at once.
        Endpoint endpointNotYetDue =
                new Endpoint("testing-notyetdue", "https://example.com/notyetdue", "whsec_notyetdue", app);
        Delivery overdueDelivery = new Delivery(app, message, endpoint);
        Attempt overdue = new Attempt(app, message, endpoint, overdueDelivery, 2);
        overdue.setStatus(AttemptStatus.SCHEDULED);
        overdue.setNextRetryAt(Instant.now().minusSeconds(3600));

        Delivery notYetDueDelivery = new Delivery(app, message, endpointNotYetDue);
        Attempt notYetDue = new Attempt(app, message, endpointNotYetDue, notYetDueDelivery, 2);
        notYetDue.setStatus(AttemptStatus.SCHEDULED);
        notYetDue.setNextRetryAt(Instant.now().plusSeconds(3600));

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(endpointNotYetDue);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(overdueDelivery);
        testEntityManager.persistAndFlush(notYetDueDelivery);
        testEntityManager.persistAndFlush(overdue);
        testEntityManager.persistAndFlush(notYetDue);

        // Act
        List<Attempt> result = underTest.findByStatusAndNextRetryAtBefore(
                AttemptStatus.SCHEDULED, Instant.now(), Limit.of(100));

        // Assert
        assertEquals(1, result.size());
        assertEquals(overdue.getId(), result.get(0).getId());
    }

    @Test
    void resetScheduled_returns1AndFlipsToCreated_whenOverdue() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 2);
        attempt.setStatus(AttemptStatus.SCHEDULED);
        attempt.setNextRetryAt(Instant.now().minusSeconds(3600));

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        // Act
        int rowsAffected = underTest.resetScheduled(attempt.getId(), Instant.now(), Instant.now());

        // Assert
        assertEquals(1, rowsAffected);
        assertEquals(AttemptStatus.CREATED, underTest.findById(attempt.getId()).get().getStatus());
    }

    @Test
    void resetScheduled_returns0_whenNotYetDue() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 2);
        attempt.setStatus(AttemptStatus.SCHEDULED);
        attempt.setNextRetryAt(Instant.now().plusSeconds(3600));

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        // Act
        int rowsAffected = underTest.resetScheduled(attempt.getId(), Instant.now(), Instant.now());

        // Assert
        assertEquals(0, rowsAffected);
        assertEquals(AttemptStatus.SCHEDULED, underTest.findById(attempt.getId()).get().getStatus());
    }

    @Test
    void touchCreated_returns1AndAdvancesUpdatedAt_whenStillCreated() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        backdateUpdatedAt(attempt.getId(), Instant.now().minusSeconds(3600));

        // Act
        Instant now = Instant.now();
        int rowsAffected = underTest.touchCreated(attempt.getId(), now);

        // Assert
        assertEquals(1, rowsAffected);
        Attempt reloaded = underTest.findById(attempt.getId()).get();
        assertEquals(now.truncatedTo(ChronoUnit.MILLIS), reloaded.getUpdatedAt().truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void touchCreated_returns0_whenNoLongerCreated() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        underTest.claim(attempt.getId(), Instant.now()); // moves it to IN_FLIGHT

        // Act
        int rowsAffected = underTest.touchCreated(attempt.getId(), Instant.now());

        // Assert
        assertEquals(0, rowsAffected);
    }

    @Test
    void touchDeadLetterCandidate_returns1AndAdvancesUpdatedAt_whenDeadAndStaleAndNotYetNotified() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 6);
        attempt.setStatus(AttemptStatus.DEAD);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        backdateUpdatedAt(attempt.getId(), Instant.now().minusSeconds(3600));

        // Act
        Instant now = Instant.now();
        int rowsAffected = underTest.touchDeadLetterCandidate(attempt.getId(), Instant.now().minusSeconds(60), now);

        // Assert
        assertEquals(1, rowsAffected);
        Attempt reloaded = underTest.findById(attempt.getId()).get();
        assertEquals(now.truncatedTo(ChronoUnit.MILLIS), reloaded.getUpdatedAt().truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void touchDeadLetterCandidate_returns0_whenAlreadyNotified() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 6);
        attempt.setStatus(AttemptStatus.DEAD);
        attempt.setDeadLetterNotifiedAt(Instant.now());

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        backdateUpdatedAt(attempt.getId(), Instant.now().minusSeconds(3600));

        // Act
        int rowsAffected = underTest.touchDeadLetterCandidate(attempt.getId(), Instant.now().minusSeconds(60),
                Instant.now());

        // Assert
        assertEquals(0, rowsAffected);
    }

    @Test
    void touchDeadLetterCandidate_returns0_whenNotStaleEnough() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 6);
        attempt.setStatus(AttemptStatus.DEAD);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt); // updated_at is "now"

        // Act
        int rowsAffected = underTest.touchDeadLetterCandidate(attempt.getId(), Instant.now().minusSeconds(60),
                Instant.now());

        // Assert
        assertEquals(0, rowsAffected);
    }

    @Test
    void findByStatusAndDeadLetterNotifiedAtIsNullAndUpdatedAtBefore_returnsOnlyStaleUnnotifiedDeadRows()
            throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));

        // All three share one Delivery: same (message, endpoint) pair, and deliveries has a real
        // unique constraint on (message_id, endpoint_id).
        Delivery delivery = new Delivery(app, message, endpoint);

        Attempt staleUnnotified = new Attempt(app, message, endpoint, delivery, 6);
        staleUnnotified.setStatus(AttemptStatus.DEAD);

        Attempt staleButNotified = new Attempt(app, message, endpoint, delivery, 6);
        staleButNotified.setStatus(AttemptStatus.DEAD);
        staleButNotified.setDeadLetterNotifiedAt(Instant.now());

        Attempt freshUnnotified = new Attempt(app, message, endpoint, delivery, 6);
        freshUnnotified.setStatus(AttemptStatus.DEAD);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(staleUnnotified);
        testEntityManager.persistAndFlush(staleButNotified);
        testEntityManager.persistAndFlush(freshUnnotified);

        Instant longAgo = Instant.now().minusSeconds(3600);
        backdateUpdatedAt(staleUnnotified.getId(), longAgo);
        backdateUpdatedAt(staleButNotified.getId(), longAgo);

        // Act
        List<Attempt> result = underTest.findByStatusAndDeadLetterNotifiedAtIsNullAndUpdatedAtBefore(
                AttemptStatus.DEAD, Instant.now().minusSeconds(60), Limit.of(100));

        // Assert
        assertEquals(1, result.size());
        assertEquals(staleUnnotified.getId(), result.get(0).getId());
    }

    @Test
    void claimDeadLetterNotification_returns1AndSetsNotifiedAt_whenNotYetNotified() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 6);
        attempt.setStatus(AttemptStatus.DEAD);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        // Act
        Instant now = Instant.now();
        int rowsAffected = underTest.claimDeadLetterNotification(attempt.getId(), now);

        // Assert
        assertEquals(1, rowsAffected);
        Attempt reloaded = underTest.findById(attempt.getId()).get();
        assertEquals(now.truncatedTo(ChronoUnit.MILLIS),
                reloaded.getDeadLetterNotifiedAt().truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void claimDeadLetterNotification_returns0_whenAlreadyClaimed() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 6);
        attempt.setStatus(AttemptStatus.DEAD);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        underTest.claimDeadLetterNotification(attempt.getId(), Instant.now());

        // Act - simulates a second, redelivered message for the same attempt
        int rowsAffected = underTest.claimDeadLetterNotification(attempt.getId(), Instant.now());

        // Assert
        assertEquals(0, rowsAffected);
    }

    @Test
    void findByDeliveryId_returnsAttemptsForThatDeliveryOnly() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        // idx_attempts_one_active_per_message_endpoint allows only one active (CREATED/IN_FLIGHT/
        // SCHEDULED) row per (message_id, endpoint_id) pair, so the first of the two same-delivery
        // attempts must be terminal before the second is persisted.
        Endpoint otherEndpoint = new Endpoint("other", "https://example.com/other", "whsec_other", app);
        Delivery delivery = new Delivery(app, message, endpoint);
        Delivery otherDelivery = new Delivery(app, message, otherEndpoint);
        Attempt firstAttempt = new Attempt(app, message, endpoint, delivery, 1);
        firstAttempt.setStatus(AttemptStatus.SUCCEEDED);
        Attempt secondAttempt = new Attempt(app, message, endpoint, delivery, 2);
        Attempt otherDeliveryAttempt = new Attempt(app, message, otherEndpoint, otherDelivery, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(otherEndpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(otherDelivery);
        testEntityManager.persistAndFlush(firstAttempt);
        testEntityManager.persistAndFlush(secondAttempt);
        testEntityManager.persistAndFlush(otherDeliveryAttempt);

        // Act
        Page<Attempt> result = underTest.findByDeliveryId(delivery.getId(),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "attemptNo")));

        // Assert
        assertEquals(2, result.getTotalElements());
        List<UUID> ids = result.getContent().stream().map(Attempt::getId).toList();
        assertTrue(ids.contains(firstAttempt.getId()));
        assertTrue(ids.contains(secondAttempt.getId()));
        assertFalse(ids.contains(otherDeliveryAttempt.getId()));
    }

    @Test
    void findByIdAndDeliveryIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId_returnsAttempt_whenAllMatch()
            throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        // Act
        Optional<Attempt> result = underTest.findByIdAndDeliveryIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(
                attempt.getId(), delivery.getId(), app.getId(), environment.getId(), user.getId());

        // Assert
        assertTrue(result.isPresent());
        assertEquals(attempt.getId(), result.get().getId());
    }

    @Test
    void findByIdAndDeliveryIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId_returnsEmpty_whenDeliveryDoesNotMatch()
            throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        // Act
        Optional<Attempt> result = underTest.findByIdAndDeliveryIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(
                attempt.getId(), UUID.randomUUID(), app.getId(), environment.getId(), user.getId());

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void findByIdAndDeliveryIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId_returnsEmpty_whenEnvironmentDoesNotMatch()
            throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment1 = new Environment("Env 1", "Desc 1", user);
        Environment environment2 = new Environment("Env 2", "Desc 2", user);
        App app = new App("App 1", environment1);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment1);
        testEntityManager.persistAndFlush(environment2);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        // Act
        Optional<Attempt> result = underTest.findByIdAndDeliveryIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(
                attempt.getId(), delivery.getId(), app.getId(), environment2.getId(), user.getId());

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void findByIdAndDeliveryIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId_returnsEmpty_whenUserDoesNotMatch()
            throws Exception {
        User user1 = new User("some_email@mail.com", "someHash");
        User user2 = new User("someone_else@mail.com", "otherHash");
        Environment environment = new Environment("Env 1", "Desc 1", user1);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 1);

        testEntityManager.persistAndFlush(user1);
        testEntityManager.persistAndFlush(user2);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        // Act
        Optional<Attempt> result = underTest.findByIdAndDeliveryIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(
                attempt.getId(), delivery.getId(), app.getId(), environment.getId(), user2.getId());

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void findByIdAndDeliveryIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId_returnsEmpty_whenAppDoesNotMatch()
            throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app1 = new App("App 1", environment);
        App app2 = new App("App 2", environment);
        Event event = new Event("some.event", app1);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app1);
        objectMapper = new ObjectMapper();
        Message message = new Message(app1, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app1, message, endpoint);
        Attempt attempt = new Attempt(app1, message, endpoint, delivery, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app1);
        testEntityManager.persistAndFlush(app2);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        // Act
        Optional<Attempt> result = underTest.findByIdAndDeliveryIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(
                attempt.getId(), delivery.getId(), app2.getId(), environment.getId(), user.getId());

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void existsByMessageIdAndEndpointIdAndStatusIn_returnsTrue_whenAnActiveRowExists() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);

        // Act & Assert
        assertTrue(underTest.existsByMessageIdAndEndpointIdAndStatusIn(message.getId(), endpoint.getId(),
                List.of(AttemptStatus.CREATED, AttemptStatus.IN_FLIGHT, AttemptStatus.SCHEDULED)));
        assertFalse(underTest.existsByMessageIdAndEndpointIdAndStatusIn(message.getId(), endpoint.getId(),
                List.of(AttemptStatus.DEAD)));
    }

    private void backdateUpdatedAt(UUID attemptId, Instant when) {
        testEntityManager.getEntityManager()
                .createNativeQuery("UPDATE attempts SET updated_at = :when WHERE id = :id")
                .setParameter("when", when)
                .setParameter("id", attemptId)
                .executeUpdate();
        testEntityManager.getEntityManager().clear();
    }
}
