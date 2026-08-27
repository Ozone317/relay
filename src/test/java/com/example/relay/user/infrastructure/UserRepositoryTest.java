package com.example.relay.user.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import com.example.relay.user.domain.User;

@DataJpaTest
public class UserRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UserRepository underTest;

    @Test
    void findByEmail_returnsUser_whenEmailExists() {

        // Arrange
        String email = "dakshkant8@gmail.com";
        User user = new User(email, "passwordHash");
        entityManager.persistAndFlush(user);

        // Act
        Optional<User> result = underTest.findByEmail(email);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(email, result.get().getEmail());
    }

    @Test
    void save_returnsEmpty_whenEmailDoesNotMatchAnyUser() {

        //Arrange
        String email = "dakshkant8@gmail.com";
        User user = new User("testemail@mail.com", "passwordHash");
        entityManager.persistAndFlush(user);

        // Act
        Optional<User> result = underTest.findByEmail(email);

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void findByEmail_throwsDataIntegrityViolationException_whenEmailAlreadyExists() {

        // Arrange
        String email = "dakshkant8@gmail.com";
        User user = new User(email, "passwordHash");
        entityManager.persistAndFlush(user);

        // Act + Assert
        assertThrows(DataIntegrityViolationException.class, () -> {
            underTest.saveAndFlush(new User(email, "someOtherHash"));
        });
    }
}
