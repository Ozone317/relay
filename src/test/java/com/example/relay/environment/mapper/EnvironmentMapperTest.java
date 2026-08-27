package com.example.relay.environment.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.relay.environment.api.dto.EnvironmentCreateDto;
import com.example.relay.environment.api.dto.EnvironmentResponseDto;
import com.example.relay.environment.domain.Environment;
import com.example.relay.user.domain.User;

public class EnvironmentMapperTest {

    private EnvironmentMapper environmentMapper;

    @BeforeEach
    void setUp() {
        environmentMapper = new EnvironmentMapper();
    }

    @Test
    void shouldMapEnvironmentToResponseDto() {
        // Arrange: construct an Environment (need a User too, for the constructor)

        Environment environment = new Environment(
            "Test Env 1",
            "This is the description",
            new User(
                "dakshkant8@gmail.com",
                "passwordhash"
            )
        );

        // Act: call environmentMapper.toResponseDto()
        EnvironmentResponseDto responseDto = environmentMapper.toResponseDto(environment);

        // Assert: check each field on the returned EnvironmentResponseDto matches
        assertEquals("Test Env 1", responseDto.name());
        assertEquals("This is the description", responseDto.description());
        assertEquals(environment.getUpdatedAt(), responseDto.updatedAt());
        assertEquals(environment.getId(), responseDto.id());
    }

    @Test
    void shouldMapEnvironmentCreateDtoToEnvironment() {
        // Arrange
        EnvironmentCreateDto dto = new EnvironmentCreateDto("Test Env 1", "Test description");
        User user = new User("dakshkant8@gmail.com", "passwordhash");

        // Act
        Environment environment = environmentMapper.toEntity(dto, user);

        // Assert
        assertEquals(dto.name(), environment.getName());
        assertEquals(dto.description(), environment.getDescription());
        assertEquals(user, environment.getUser());
    }

    @Test
    void shouldMapListOfEnvironmentObjectsToListOfDtoObjects() {
        // Arrange
        List<Environment> environments = new ArrayList<>();
        User user = new User("dakshkant8@gmail.com", "passwordhash");
        environments.add(new Environment("Test Env 1", "Test description 1", user));
        environments.add(new Environment("Test Env 2", "Test description 2", user));

        // Act
        List<EnvironmentResponseDto> dtos = environmentMapper.toResponseDtoList(environments);

        // Assert
        assertEquals(environments.size(), dtos.size());
        assertEquals("Test Env 1", dtos.get(0).name());
        assertEquals("Test description 1", dtos.get(0).description());
        assertEquals("Test Env 2", dtos.get(1).name());
        assertEquals("Test description 2", dtos.get(1).description());
    }
}
