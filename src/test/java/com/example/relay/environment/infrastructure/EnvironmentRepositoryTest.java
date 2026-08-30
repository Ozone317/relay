package com.example.relay.environment.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.environment.domain.Environment;
import com.example.relay.user.domain.User;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

@DataJpaTest
public class EnvironmentRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EnvironmentRepository underTest;

    @Test
    void findAllByUserId_returnsOnlyEnvironmentsBelongingToThatUser() {
        // Arrange
        User user1 = new User("testuser1@mail.com", "passwordhash");
        User user2 = new User("testuser2@mail.com", "passwordhash");
        Environment env1 = new Environment("Env 1", "Desc 1", user1);
        Environment env2 = new Environment("Env 2", "Desc 2", user1);
        Environment env3 = new Environment("Env 3", "Desc 3", user2);

        entityManager.persistAndFlush(user1);
        entityManager.persistAndFlush(user2);
        entityManager.persistAndFlush(env1);
        entityManager.persistAndFlush(env2);
        entityManager.persistAndFlush(env3);

        // Act
        List<Environment> result = underTest.findAllByUserId(user1.getId());

        // Assert
        assertFalse(result.isEmpty());
        assertEquals(env1, result.get(0));
        assertEquals(env2, result.get(1));
    }

    @Test
    void findByIdAndUserId_returnsEnvironment_whenIdAndUserMatch() {
        // Arrange
        User user1 = new User("testuser1@mail.com", "passwordhash");
        User user2 = new User("testuser2@mail.com", "passwordhash");
        Environment env1 = new Environment("Env 1", "Desc 1", user1);
        Environment env2 = new Environment("Env 2", "Desc 2", user1);
        Environment env3 = new Environment("Env 3", "Desc 3", user2);

        entityManager.persistAndFlush(user1);
        entityManager.persistAndFlush(user2);
        entityManager.persistAndFlush(env1);
        entityManager.persistAndFlush(env2);
        entityManager.persistAndFlush(env3);

        // Act
        Optional<Environment> result = underTest.findByIdAndUserId(env1.getId(), user1.getId());

        // Assert
        assertFalse(result.isEmpty());
        assertEquals(env1, result.get());
    }

    @Test
    void findByIdAndUserId_returnsEmpty_whenEnvironmentBelongsToADifferentUser() {
        // Arrange
        User user1 = new User("testuser1@mail.com", "passwordhash");
        User user2 = new User("testuser2@mail.com", "passwordhash");
        Environment env1 = new Environment("Env 1", "Desc 1", user1);

        entityManager.persistAndFlush(user1);
        entityManager.persistAndFlush(user2);
        entityManager.persistAndFlush(env1);

        // Act
        Optional<Environment> result = underTest.findByIdAndUserId(env1.getId(), user2.getId());

        // Assert
        assertTrue(result.isEmpty());
    }
}
