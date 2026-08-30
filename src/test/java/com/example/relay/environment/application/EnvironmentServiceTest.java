package com.example.relay.environment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.relay.environment.api.dto.EnvironmentCreateDto;
import com.example.relay.environment.domain.Environment;
import com.example.relay.environment.exception.EnvironmentNotFoundException;
import com.example.relay.environment.infrastructure.EnvironmentRepository;
import com.example.relay.environment.mapper.EnvironmentMapper;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class EnvironmentServiceTest {

    @Mock
    private EnvironmentRepository environmentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EnvironmentMapper environmentMapper;

    @InjectMocks
    private EnvironmentService underTest;

    @Test
    void create_savesMappedEnvironmentAndReturnsIt() {

        // Arrange
        EnvironmentCreateDto request = new EnvironmentCreateDto("Test Env", "Test description");
        User user = new User("dakshkant8@gmail.com", "passwordhash");
        UUID userId = user.getId();
        Environment environment = new Environment("Test Env", "Test description", user);

        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(environmentMapper.toEntity(request, user)).thenReturn(environment);
        when(environmentRepository.save(environment)).thenReturn(environment);

        // Act
        Environment savedEnvironment = underTest.create(request, userId);

        // Assert
        assertEquals(userId, savedEnvironment.getUser().getId());
        assertEquals("Test Env", savedEnvironment.getName());
        assertEquals("Test description", savedEnvironment.getDescription());
        verify(environmentRepository).save(environment);
    }

    @Test
    void getAll_fetchesAndReturnsAllEnvironmentsCreatedByTheGivenUser() {

        // Arrange
        User user = new User("dakshkant8@gmail.com", "passwordhash");
        UUID userId = user.getId();

        List<Environment> environments = List.of(new Environment("Environment 1", "Description 1", user),
                new Environment("Environment 2", "Description 2", user));

        when(environmentRepository.findAllByUserId(userId)).thenReturn(environments);

        // Act
        List<Environment> fetchedEnvironments = underTest.getAll(userId);

        // Assert
        assertEquals(fetchedEnvironments.get(0).getName(), "Environment 1");
        assertEquals(fetchedEnvironments.get(0).getDescription(), "Description 1");
        assertEquals(fetchedEnvironments.get(0).getUser().getId(), userId);
        assertEquals(fetchedEnvironments.get(1).getName(), "Environment 2");
        assertEquals(fetchedEnvironments.get(1).getDescription(), "Description 2");
        assertEquals(fetchedEnvironments.get(1).getUser().getId(), userId);
    }

    @Test
    void getById_fetchesAndReturnsEnvironmentByItsIdAndUserId_whenItExists() {

        // Arrange
        User user = new User("dakshkant8@gmail.com", "passwordhash");
        UUID userId = user.getId();
        Environment environment = new Environment("Environment 1", "Description 1", user);

        when(environmentRepository.findByIdAndUserId(environment.getId(), userId)).thenReturn(Optional.of(environment));

        // Act
        Environment fetchedEnvironment = underTest.getById(environment.getId(), userId);

        // Assert
        assertEquals(fetchedEnvironment.getName(), "Environment 1");
        assertEquals(fetchedEnvironment.getDescription(), "Description 1");
        assertEquals(fetchedEnvironment.getUser().getId(), userId);
    }

    @Test
    void getById_throwsEnvironmentNotFoundException_whenEnvironmentDoesNotExist() {

        // Arrange
        User user = new User("dakshkant8@gmail.com", "passwordhash");
        UUID userId = user.getId();
        Environment environment = new Environment("Environment 1", "Description 1", user);

        when(environmentRepository.findByIdAndUserId(environment.getId(), userId)).thenReturn(Optional.empty());

        // Act + Assert
        assertThrows(EnvironmentNotFoundException.class, () -> {
            underTest.getById(environment.getId(), userId);
        });
    }

    @Test
    void updateDescription_updatesEnvironmentDescriptionAndReturnsIt() {

        // Arrange
        String updatedDescription = "Updated description";
        User user = new User("dakshkant8@gmail.com", "passwordHash");
        UUID userId = user.getId();
        Environment environment = new Environment("Environment 1", "Description 1", user);
        UUID environmentId = environment.getId();

        when(environmentRepository.findByIdAndUserId(environmentId, userId)).thenReturn(Optional.of(environment));
        when(environmentRepository.save(environment)).thenReturn(environment);

        // Act
        Environment fetchedEnvironment = underTest.updateDescription(environmentId, updatedDescription, userId);

        // Assert
        assertEquals(fetchedEnvironment.getDescription(), updatedDescription);
    }

    @Test
    void delete_deletesEnvironment() {

        // Arrange
        User user = new User("dakshkant8@gmail.com", "passwordhash");
        Environment environment = new Environment("Environment 1", "Description 1", user);

        when(environmentRepository.findByIdAndUserId(environment.getId(), user.getId()))
                .thenReturn(Optional.of(environment));

        // Act
        underTest.delete(environment.getId(), user.getId());

        // Assert
        verify(environmentRepository).delete(environment);
    }
}
