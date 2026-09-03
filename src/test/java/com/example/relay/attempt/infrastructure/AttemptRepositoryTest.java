package com.example.relay.attempt.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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

    private void backdateUpdatedAt(UUID attemptId, Instant when) {
        testEntityManager.getEntityManager()
                .createNativeQuery("UPDATE attempts SET updated_at = :when WHERE id = :id")
                .setParameter("when", when)
                .setParameter("id", attemptId)
                .executeUpdate();
        testEntityManager.getEntityManager().clear();
    }
}
