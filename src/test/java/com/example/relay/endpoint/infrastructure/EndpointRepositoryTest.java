package com.example.relay.endpoint.infrastructure;

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
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.user.domain.User;

@DataJpaTest
public class EndpointRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EndpointRepository underTest;

    @Test
    void findAllByAppIdAndEnvironmentIdAndUserId_returnsAllEndpoints_whenAppEnvironmentAndUserMatch() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint1 = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Endpoint endpoint2 = new Endpoint("Staging", "https://staging.example.com/webhook", "whsec_2", app);

        entityManager.persistAndFlush(user);
        entityManager.persistAndFlush(env);
        entityManager.persistAndFlush(app);
        entityManager.persistAndFlush(endpoint1);
        entityManager.persistAndFlush(endpoint2);

        // Act
        List<Endpoint> result = underTest.findAllByAppIdAndEnvironmentIdAndUserId(app.getId(), env.getId(), user.getId());

        // Assert
        assertEquals(2, result.size());
    }

    @Test
    void findAllByAppIdAndEnvironmentIdAndUserId_returnsEmpty_whenEnvironmentDoesNotMatch() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env1 = new Environment("Env 1", "Desc 1", user);
        Environment env2 = new Environment("Env 2", "Desc 2", user);
        App app = new App("App 1", env1);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);

        entityManager.persistAndFlush(user);
        entityManager.persistAndFlush(env1);
        entityManager.persistAndFlush(env2);
        entityManager.persistAndFlush(app);
        entityManager.persistAndFlush(endpoint);

        // Act
        List<Endpoint> result = underTest.findAllByAppIdAndEnvironmentIdAndUserId(app.getId(), env2.getId(), user.getId());

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void findByIdAndAppIdAndEnvironmentIdAndUserId_returnsEndpoint_whenAllMatch() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);

        entityManager.persistAndFlush(user);
        entityManager.persistAndFlush(env);
        entityManager.persistAndFlush(app);
        entityManager.persistAndFlush(endpoint);

        // Act
        Optional<Endpoint> result = underTest.findByIdAndAppIdAndEnvironmentIdAndUserId(endpoint.getId(), app.getId(), env.getId(), user.getId());

        // Assert
        assertFalse(result.isEmpty());
        assertEquals(endpoint, result.get());
    }

    @Test
    void findByIdAndAppIdAndEnvironmentIdAndUserId_returnsEmpty_whenEnvironmentDoesNotMatch() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env1 = new Environment("Env 1", "Desc 1", user);
        Environment env2 = new Environment("Env 2", "Desc 2", user);
        App app = new App("App 1", env1);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);

        entityManager.persistAndFlush(user);
        entityManager.persistAndFlush(env1);
        entityManager.persistAndFlush(env2);
        entityManager.persistAndFlush(app);
        entityManager.persistAndFlush(endpoint);

        // Act
        Optional<Endpoint> result = underTest.findByIdAndAppIdAndEnvironmentIdAndUserId(endpoint.getId(), app.getId(), env2.getId(), user.getId());

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
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);

        entityManager.persistAndFlush(user1);
        entityManager.persistAndFlush(user2);
        entityManager.persistAndFlush(env);
        entityManager.persistAndFlush(app);
        entityManager.persistAndFlush(endpoint);

        // Act
        Optional<Endpoint> result = underTest.findByIdAndAppIdAndEnvironmentIdAndUserId(endpoint.getId(), app.getId(), env.getId(), user2.getId());

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void findByNameAndAppIdAndEnvironmentIdAndUserId_returnsEndpoint_whenAllMatch() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);

        entityManager.persistAndFlush(user);
        entityManager.persistAndFlush(env);
        entityManager.persistAndFlush(app);
        entityManager.persistAndFlush(endpoint);

        // Act
        Optional<Endpoint> result = underTest.findByNameAndAppIdAndEnvironmentIdAndUserId("Production", app.getId(), env.getId(), user.getId());

        // Assert
        assertFalse(result.isEmpty());
        assertEquals(endpoint, result.get());
    }

    @Test
    void findByNameAndAppIdAndEnvironmentIdAndUserId_returnsEmpty_whenNameDoesNotMatch() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);

        entityManager.persistAndFlush(user);
        entityManager.persistAndFlush(env);
        entityManager.persistAndFlush(app);
        entityManager.persistAndFlush(endpoint);

        // Act
        Optional<Endpoint> result = underTest.findByNameAndAppIdAndEnvironmentIdAndUserId("Nonexistent", app.getId(), env.getId(), user.getId());

        // Assert
        assertTrue(result.isEmpty());
    }
}
