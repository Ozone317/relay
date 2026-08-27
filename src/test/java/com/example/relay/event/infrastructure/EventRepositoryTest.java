package com.example.relay.event.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.example.relay.app.domain.App;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.user.domain.User;

@DataJpaTest
public class EventRepositoryTest {

    @Autowired
    TestEntityManager testEntityManager;

    @Autowired
    EventRepository underTest;

    @Test
    void findAllByAppId_returnsAllEventsBelongingToTheApp() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event1 = new Event("payment.completed", app);
        Event event2 = new Event("payment.requested", app);
        Event event3 = new Event("user.created", app);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(env);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event1);
        testEntityManager.persistAndFlush(event2);
        testEntityManager.persistAndFlush(event3);

        // Act
        List<Event> result = underTest.findAllByAppId(app.getId());

        // Assert
        assertEquals(result.size(), 3);
        assertEquals(event1, result.get(0));
        assertEquals(event2, result.get(1));
        assertEquals(event3, result.get(2));
    }

    @Test
    void findByNameAndAppId_returnsEventIfItExists() {
        // Arrange
        User user = new User("test@mail.com", "somePassword");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.created", app);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(env);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);

        // Act
        Optional<Event> result = underTest.findByNameAndAppId(event.getName(), app.getId());

        // Assert
        assertFalse(result.isEmpty());
        assertEquals(event.getName(), result.get().getName());
        assertEquals(event.getApp().getId(), result.get().getApp().getId());
    }

    @Test
    void findByNameAndAppId_returnsEmptyIfItDoesNotExist() {
        // Arrange
        User user = new User("test@mail.com", "somePassword");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.created", app);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(env);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);

        // Act
        Optional<Event> result = underTest.findByNameAndAppId("user.created", app.getId());

        // Assert
        assertTrue(result.isEmpty());
    }
}
