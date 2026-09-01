package com.example.relay.attempt.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        int rowsAffected = underTest.claim(attempt.getId());

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

        underTest.claim(attempt.getId());

        // Act (claiming the already claimed row)
        int rowsAffected = underTest.claim(attempt.getId());

        // Assert
        assertEquals(0, rowsAffected);
    }
}
