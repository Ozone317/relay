package com.example.relay.app.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.app.domain.App;
import com.example.relay.environment.domain.Environment;
import com.example.relay.user.domain.User;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

@DataJpaTest
public class AppRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private AppRepository underTest;

    @Test
    void findAllByEnvironmentId_returnsAllAppsBelongingToTheEnvironment() {
        // Arrange
        User user1 = new User("test@mail.com", "passwordHash");
        User user2 = new User("test2@mail.com", "someOtherHash");

        Environment env1 = new Environment("Env 1", "Desc 1", user1);
        Environment env2 = new Environment("Env 2", "Desc 2", user2);

        App app1 = new App("App 1", env1);
        App app2 = new App("App 2", env1);
        App app3 = new App("App 3", env2);

        entityManager.persistAndFlush(user1);
        entityManager.persistAndFlush(user2);
        entityManager.persistAndFlush(env1);
        entityManager.persistAndFlush(env2);
        entityManager.persistAndFlush(app1);
        entityManager.persistAndFlush(app2);
        entityManager.persistAndFlush(app3);

        // Act
        List<App> result = underTest.findAllByEnvironmentId(env1.getId());

        // Assert
        assertEquals(result.size(), 2);
        assertEquals(app1, result.get(0));
        assertEquals(app2, result.get(1));
    }

    @Test
    void findByIdAndEnvironmentIdAndEnvironmentUserId_returnsTheApp_whenIdEnvironmentAndUserAllMatch() {
        // Arrange
        User user1 = new User("test@mail.com", "passwordHash");
        Environment env1 = new Environment("Env 1", "Desc 1", user1);
        App app1 = new App("App 1", env1);

        entityManager.persistAndFlush(user1);
        entityManager.persistAndFlush(env1);
        entityManager.persistAndFlush(app1);

        // Act
        Optional<App> result =
                underTest.findByIdAndEnvironmentIdAndEnvironmentUserId(app1.getId(), env1.getId(), user1.getId());

        // Assert
        assertFalse(result.isEmpty());
        assertEquals(app1, result.get());
    }

    @Test
    void findByIdAndEnvironmentIdAndEnvironmentUserId_returnsEmpty_whenAppBelongsToUserButNotToTheGivenEnvironment() {
        // Arrange
        User user1 = new User("test@mail.com", "passwordHash");
        Environment env1 = new Environment("Env 1", "Desc 1", user1);
        Environment env2 = new Environment("Env 2", "Desc 2", user1);
        App app1 = new App("App 1", env1);

        entityManager.persistAndFlush(user1);
        entityManager.persistAndFlush(env1);
        entityManager.persistAndFlush(env2);
        entityManager.persistAndFlush(app1);

        // Act
        Optional<App> result =
                underTest.findByIdAndEnvironmentIdAndEnvironmentUserId(app1.getId(), env2.getId(), user1.getId());

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void findByIdAndEnvironmentIdAndEnvironmentUserId_returnsEmpty_whenEnvironmentMatchesButUserDoesNot() {
        // Arrange
        User user1 = new User("test@mail.com", "passwordHash");
        User user2 = new User("test2@mail.com", "someOtherHash");
        Environment env1 = new Environment("Env 1", "Desc 1", user1);
        App app1 = new App("App 1", env1);

        entityManager.persistAndFlush(user1);
        entityManager.persistAndFlush(user2);
        entityManager.persistAndFlush(env1);
        entityManager.persistAndFlush(app1);

        // Act
        Optional<App> result =
                underTest.findByIdAndEnvironmentIdAndEnvironmentUserId(app1.getId(), env1.getId(), user2.getId());

        // Assert
        assertTrue(result.isEmpty());
    }
}
