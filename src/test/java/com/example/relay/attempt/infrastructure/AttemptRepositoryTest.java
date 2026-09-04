package com.example.relay.attempt.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.relay.app.domain.App;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.message.domain.Message;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@DataJpaTest
public class AttemptRepositoryTest {

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
        Attempt attempt = new Attempt(app, message, endpoint, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
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
        Attempt attempt = new Attempt(app, message, endpoint, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
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

        Attempt stale = new Attempt(app, message, endpoint, 1);
        Attempt fresh = new Attempt(app, message, endpoint, 1);
        Attempt staleButWrongStatus = new Attempt(app, message, endpoint, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
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
            Attempt attempt = new Attempt(app, message, endpoint, 1);
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
        Attempt attempt = new Attempt(app, message, endpoint, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
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
        Attempt attempt = new Attempt(app, message, endpoint, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
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
        Attempt attempt = new Attempt(app, message, endpoint, 1); // stays CREATED

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
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
        Attempt attempt = new Attempt(app, message, endpoint, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
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
        Attempt attempt = new Attempt(app, message, endpoint, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
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
        Attempt attempt = new Attempt(app, message, endpoint, 2);
        attempt.setStatus(AttemptStatus.SCHEDULED);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
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

        Attempt overdue = new Attempt(app, message, endpoint, 2);
        overdue.setStatus(AttemptStatus.SCHEDULED);
        overdue.setNextRetryAt(Instant.now().minusSeconds(3600));

        Attempt notYetDue = new Attempt(app, message, endpoint, 2);
        notYetDue.setStatus(AttemptStatus.SCHEDULED);
        notYetDue.setNextRetryAt(Instant.now().plusSeconds(3600));

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
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
        Attempt attempt = new Attempt(app, message, endpoint, 2);
        attempt.setStatus(AttemptStatus.SCHEDULED);
        attempt.setNextRetryAt(Instant.now().minusSeconds(3600));

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
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
        Attempt attempt = new Attempt(app, message, endpoint, 2);
        attempt.setStatus(AttemptStatus.SCHEDULED);
        attempt.setNextRetryAt(Instant.now().plusSeconds(3600));

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
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
        Attempt attempt = new Attempt(app, message, endpoint, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
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
        Attempt attempt = new Attempt(app, message, endpoint, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
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
        Attempt attempt = new Attempt(app, message, endpoint, 6);
        attempt.setStatus(AttemptStatus.DEAD);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
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
        Attempt attempt = new Attempt(app, message, endpoint, 6);
        attempt.setStatus(AttemptStatus.DEAD);
        attempt.setDeadLetterNotifiedAt(Instant.now());

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
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
        Attempt attempt = new Attempt(app, message, endpoint, 6);
        attempt.setStatus(AttemptStatus.DEAD);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
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

        Attempt staleUnnotified = new Attempt(app, message, endpoint, 6);
        staleUnnotified.setStatus(AttemptStatus.DEAD);

        Attempt staleButNotified = new Attempt(app, message, endpoint, 6);
        staleButNotified.setStatus(AttemptStatus.DEAD);
        staleButNotified.setDeadLetterNotifiedAt(Instant.now());

        Attempt freshUnnotified = new Attempt(app, message, endpoint, 6);
        freshUnnotified.setStatus(AttemptStatus.DEAD);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
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
        Attempt attempt = new Attempt(app, message, endpoint, 6);
        attempt.setStatus(AttemptStatus.DEAD);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
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
        Attempt attempt = new Attempt(app, message, endpoint, 6);
        attempt.setStatus(AttemptStatus.DEAD);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(attempt);

        underTest.claimDeadLetterNotification(attempt.getId(), Instant.now());

        // Act - simulates a second, redelivered message for the same attempt
        int rowsAffected = underTest.claimDeadLetterNotification(attempt.getId(), Instant.now());

        // Assert
        assertEquals(0, rowsAffected);
    }

    @Test
    void findByAppIdAndFilters_returnsAllAttempts_whenNoFiltersProvided() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Attempt attempt1 = new Attempt(app, message, endpoint, 1);
        Attempt attempt2 = new Attempt(app, message, endpoint, 2);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(attempt1);
        testEntityManager.persistAndFlush(attempt2);

        // Act
        Page<Attempt> result =
                underTest.findByAppIdAndFilters(app.getId(), null, null, null, null, PageRequest.of(0, 10));

        // Assert
        assertEquals(2, result.getTotalElements());
    }

    @Test
    void findByAppIdAndFilters_excludesAttemptsFromOtherApps() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app1 = new App("App 1", environment);
        App app2 = new App("App 2", environment);
        Event event1 = new Event("some.event", app1);
        Event event2 = new Event("some.event", app2);
        Endpoint endpoint1 = new Endpoint("testing", "https://example.com", "whsec_1", app1);
        Endpoint endpoint2 = new Endpoint("testing", "https://example.com", "whsec_2", app2);
        objectMapper = new ObjectMapper();
        Message message1 = new Message(app1, event1, objectMapper.readTree("{\"name\": \"hello\"}"));
        Message message2 = new Message(app2, event2, objectMapper.readTree("{\"name\": \"hello\"}"));
        Attempt attemptInApp1 = new Attempt(app1, message1, endpoint1, 1);
        Attempt attemptInApp2 = new Attempt(app2, message2, endpoint2, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app1);
        testEntityManager.persistAndFlush(app2);
        testEntityManager.persistAndFlush(event1);
        testEntityManager.persistAndFlush(event2);
        testEntityManager.persistAndFlush(endpoint1);
        testEntityManager.persistAndFlush(endpoint2);
        testEntityManager.persistAndFlush(message1);
        testEntityManager.persistAndFlush(message2);
        testEntityManager.persistAndFlush(attemptInApp1);
        testEntityManager.persistAndFlush(attemptInApp2);

        // Act
        Page<Attempt> result =
                underTest.findByAppIdAndFilters(app1.getId(), null, null, null, null, PageRequest.of(0, 10));

        // Assert
        assertEquals(1, result.getTotalElements());
        assertEquals(attemptInApp1.getId(), result.getContent().get(0).getId());
    }

    @Test
    void findByAppIdAndFilters_filtersByEndpointId_whenProvided() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint1 = new Endpoint("testing1", "https://example.com/1", "whsec_1", app);
        Endpoint endpoint2 = new Endpoint("testing2", "https://example.com/2", "whsec_2", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Attempt attemptToEndpoint1 = new Attempt(app, message, endpoint1, 1);
        Attempt attemptToEndpoint2 = new Attempt(app, message, endpoint2, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint1);
        testEntityManager.persistAndFlush(endpoint2);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(attemptToEndpoint1);
        testEntityManager.persistAndFlush(attemptToEndpoint2);

        // Act
        Page<Attempt> result = underTest.findByAppIdAndFilters(app.getId(), endpoint1.getId(), null, null, null,
                PageRequest.of(0, 10));

        // Assert
        assertEquals(1, result.getTotalElements());
        assertEquals(attemptToEndpoint1.getId(), result.getContent().get(0).getId());
    }

    @Test
    void findByAppIdAndFilters_filtersByStatus_whenProvided() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Attempt succeeded = new Attempt(app, message, endpoint, 1);
        succeeded.setStatus(AttemptStatus.SUCCEEDED);
        Attempt dead = new Attempt(app, message, endpoint, 6);
        dead.setStatus(AttemptStatus.DEAD);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(succeeded);
        testEntityManager.persistAndFlush(dead);

        // Act
        Page<Attempt> result = underTest.findByAppIdAndFilters(app.getId(), null, AttemptStatus.DEAD, null, null,
                PageRequest.of(0, 10));

        // Assert
        assertEquals(1, result.getTotalElements());
        assertEquals(dead.getId(), result.getContent().get(0).getId());
    }

    @Test
    void findByAppIdAndFilters_includesRowsAtTheDateRangeBoundaries_inclusive() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Attempt atFrom = new Attempt(app, message, endpoint, 1);
        Attempt atTo = new Attempt(app, message, endpoint, 1);
        Attempt beforeRange = new Attempt(app, message, endpoint, 1);
        Attempt afterRange = new Attempt(app, message, endpoint, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(atFrom);
        testEntityManager.persistAndFlush(atTo);
        testEntityManager.persistAndFlush(beforeRange);
        testEntityManager.persistAndFlush(afterRange);

        Instant from = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
        Instant to = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
        backdateCreatedAt(atFrom.getId(), from);
        backdateCreatedAt(atTo.getId(), to);
        backdateCreatedAt(beforeRange.getId(), from.minusSeconds(1));
        backdateCreatedAt(afterRange.getId(), to.plusSeconds(1));

        // Act
        Page<Attempt> result =
                underTest.findByAppIdAndFilters(app.getId(), null, null, from, to, PageRequest.of(0, 10));

        // Assert
        assertEquals(2, result.getTotalElements());
        List<UUID> ids = result.getContent().stream().map(Attempt::getId).toList();
        assertTrue(ids.contains(atFrom.getId()));
        assertTrue(ids.contains(atTo.getId()));
    }

    @Test
    void findByAppIdAndFilters_sortsByCreatedAtInBothDirections() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Attempt older = new Attempt(app, message, endpoint, 1);
        Attempt newer = new Attempt(app, message, endpoint, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(older);
        testEntityManager.persistAndFlush(newer);

        backdateCreatedAt(older.getId(), Instant.now().minusSeconds(3600));
        backdateCreatedAt(newer.getId(), Instant.now());

        // Act
        Page<Attempt> ascending = underTest.findByAppIdAndFilters(app.getId(), null, null, null, null,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "createdAt")));
        Page<Attempt> descending = underTest.findByAppIdAndFilters(app.getId(), null, null, null, null,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));

        // Assert
        assertEquals(older.getId(), ascending.getContent().get(0).getId());
        assertEquals(newer.getId(), ascending.getContent().get(1).getId());
        assertEquals(newer.getId(), descending.getContent().get(0).getId());
        assertEquals(older.getId(), descending.getContent().get(1).getId());
    }

    @Test
    void findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId_returnsAttempt_whenAllMatch() throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Attempt attempt = new Attempt(app, message, endpoint, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(attempt);

        // Act
        Optional<Attempt> result = underTest.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(
                attempt.getId(), app.getId(), environment.getId(), user.getId());

        // Assert
        assertTrue(result.isPresent());
        assertEquals(attempt.getId(), result.get().getId());
    }

    @Test
    void findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId_returnsEmpty_whenEnvironmentDoesNotMatch()
            throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment1 = new Environment("Env 1", "Desc 1", user);
        Environment environment2 = new Environment("Env 2", "Desc 2", user);
        App app = new App("App 1", environment1);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Attempt attempt = new Attempt(app, message, endpoint, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment1);
        testEntityManager.persistAndFlush(environment2);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(attempt);

        // Act
        Optional<Attempt> result = underTest.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(
                attempt.getId(), app.getId(), environment2.getId(), user.getId());

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId_returnsEmpty_whenUserDoesNotMatch()
            throws Exception {
        User user1 = new User("some_email@mail.com", "someHash");
        User user2 = new User("someone_else@mail.com", "otherHash");
        Environment environment = new Environment("Env 1", "Desc 1", user1);
        App app = new App("App 1", environment);
        Event event = new Event("some.event", app);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app);
        objectMapper = new ObjectMapper();
        Message message = new Message(app, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Attempt attempt = new Attempt(app, message, endpoint, 1);

        testEntityManager.persistAndFlush(user1);
        testEntityManager.persistAndFlush(user2);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(attempt);

        // Act
        Optional<Attempt> result = underTest.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(
                attempt.getId(), app.getId(), environment.getId(), user2.getId());

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId_returnsEmpty_whenAppDoesNotMatch()
            throws Exception {
        User user = new User("some_email@mail.com", "someHash");
        Environment environment = new Environment("Env 1", "Desc 1", user);
        App app1 = new App("App 1", environment);
        App app2 = new App("App 2", environment);
        Event event = new Event("some.event", app1);
        Endpoint endpoint = new Endpoint("testing", "https://example.com", "whsec_some_secret", app1);
        objectMapper = new ObjectMapper();
        Message message = new Message(app1, event, objectMapper.readTree("{\"name\": \"hello\"}"));
        Attempt attempt = new Attempt(app1, message, endpoint, 1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app1);
        testEntityManager.persistAndFlush(app2);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
        testEntityManager.persistAndFlush(attempt);

        // Act
        Optional<Attempt> result = underTest.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(
                attempt.getId(), app2.getId(), environment.getId(), user.getId());

        // Assert
        assertTrue(result.isEmpty());
    }

    private void backdateUpdatedAt(UUID attemptId, Instant when) {
        testEntityManager.getEntityManager()
                .createNativeQuery("UPDATE attempts SET updated_at = :when WHERE id = :id")
                .setParameter("when", when)
                .setParameter("id", attemptId)
                .executeUpdate();
        testEntityManager.getEntityManager().clear();
    }

    private void backdateCreatedAt(UUID attemptId, Instant when) {
        testEntityManager.getEntityManager()
                .createNativeQuery("UPDATE attempts SET created_at = :when WHERE id = :id")
                .setParameter("when", when)
                .setParameter("id", attemptId)
                .executeUpdate();
        testEntityManager.getEntityManager().clear();
    }
}
