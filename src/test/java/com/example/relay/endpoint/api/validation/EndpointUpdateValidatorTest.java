package com.example.relay.endpoint.api.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.endpoint.api.dto.EndpointUpdateDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EndpointUpdateValidatorTest {

    private EndpointUpdateValidator underTest;

    @BeforeEach
    void setUp() {
        underTest = new EndpointUpdateValidator();
    }

    @Test
    void isValid_returnsTrue_whenDtoIsNull() {
        assertTrue(underTest.isValid(null, null));
    }

    @Test
    void isValid_returnsTrue_whenNameIsProvided() {
        EndpointUpdateDto dto = new EndpointUpdateDto("staging", null, null);
        assertTrue(underTest.isValid(dto, null));
    }

    @Test
    void isValid_returnsTrue_whenUrlIsProvided() {
        EndpointUpdateDto dto = new EndpointUpdateDto(null, "https://example.com", null);
        assertTrue(underTest.isValid(dto, null));
    }

    @Test
    void isValid_returnsTrue_whenActiveIsProvided() {
        EndpointUpdateDto dto = new EndpointUpdateDto(null, null, false);
        assertTrue(underTest.isValid(dto, null));
    }

    @Test
    void isValid_returnsFalse_whenNoFieldIsProvided() {
        EndpointUpdateDto dto = new EndpointUpdateDto(null, null, null);
        assertFalse(underTest.isValid(dto, null));
    }
}
