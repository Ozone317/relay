package com.example.relay.endpoint.utils;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SigningSecretGeneratorTest {

    private SigningSecretGenerator underTest;

    @BeforeEach
    void setUp() {
        underTest = new SigningSecretGenerator();
    }

    @Test
    void generate_returnsSecretWithWhsecPrefix() {
        // Act
        String secret = underTest.generate();

        // Assert
        assertTrue(secret.startsWith("whsec_"));
        assertTrue(secret.length() > "whsec_".length());
    }

    @Test
    void generate_returnsDifferentSecretsOnEachCall() {
        // Act
        String first = underTest.generate();
        String second = underTest.generate();

        // Assert
        assertNotEquals(first, second);
    }
}
