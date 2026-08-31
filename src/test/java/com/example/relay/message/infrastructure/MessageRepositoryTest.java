package com.example.relay.message.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.app.domain.App;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.message.domain.Message;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

@DataJpaTest
public class MessageRepositoryTest {

    @Autowired
    TestEntityManager testEntityManager;

    @Autowired
    MessageRepository underTest;

    @Test
    void findByIdAndAppIdAndEnvironmentIdAndUserId_returnsMessage_whenAppEnvironmentAndUserMatch() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        Message message = new Message(app, event, body);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(env);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(message);

        // Act
        Optional<Message> result = underTest.findByIdAndAppIdAndEnvironmentIdAndUserId(message.getId(), app.getId(),
                env.getId(), user.getId());

        // Assert
        assertTrue(result.isPresent());
        assertEquals(message.getId(), result.get().getId());
    }

    @Test
    void findByIdAndAppIdAndEnvironmentIdAndUserId_returnsEmpty_whenIdDoesNotMatch() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        Message message = new Message(app, event, body);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(env);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(message);

        // Act
        Optional<Message> result = underTest.findByIdAndAppIdAndEnvironmentIdAndUserId(UUID.randomUUID(), app.getId(),
                env.getId(), user.getId());

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void findByIdAndAppIdAndEnvironmentIdAndUserId_returnsEmpty_whenAppDoesNotMatch() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        Message message = new Message(app, event, body);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(env);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(message);

        // Act
        Optional<Message> result = underTest.findByIdAndAppIdAndEnvironmentIdAndUserId(message.getId(),
                UUID.randomUUID(), env.getId(), user.getId());

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void findByIdAndAppIdAndEnvironmentIdAndUserId_returnsEmpty_whenUserDoesNotMatch() {
        // Arrange
        User user1 = new User("test@mail.com", "passwordHash");
        User user2 = new User("test2@mail.com", "someOtherHash");
        Environment env = new Environment("Env 1", "Desc 1", user1);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        ObjectNode body = new ObjectMapper().createObjectNode().put("amount", 4999);
        Message message = new Message(app, event, body);

        testEntityManager.persistAndFlush(user1);
        testEntityManager.persistAndFlush(user2);
        testEntityManager.persistAndFlush(env);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(message);

        // Act
        Optional<Message> result = underTest.findByIdAndAppIdAndEnvironmentIdAndUserId(message.getId(), app.getId(),
                env.getId(), user2.getId());

        // Assert
        assertTrue(result.isEmpty());
    }
}
