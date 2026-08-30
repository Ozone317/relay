package com.example.relay.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

public class JwtServiceTest {

    private static final String TEST_SECRET = "test-secret-key-which-is-long-enough-for-hmac-sha-algorithms";

    private JwtService underTest;

    @BeforeEach
    void setUp() {
        underTest = new JwtService();
        ReflectionTestUtils.setField(underTest, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(underTest, "expirationMs", 3_600_000L);
    }

    @Test
    void generateToken_thenExtractEmail_roundTripsCorrectly() {

        // Arrange
        String email = "dakshkant8@gmail.com";
        UUID userId = UUID.fromString("87492bba-28ba-4850-83fe-cee99fad11be");

        // Act
        String token = underTest.generateToken(email, userId);

        // Assert
        assertEquals("dakshkant8@gmail.com", underTest.extractEmail(token));
    }

    @Test
    void generateToken_thenExtractUserId_roundTripsCorrectly() {

        // Arrange
        String email = "dakshkant8@gmail.com";
        UUID userId = UUID.fromString("87492bba-28ba-4850-83fe-cee99fad11be");

        // Act
        String token = underTest.generateToken(email, userId);

        // Assert
        assertEquals(userId, underTest.extractUserId(token));
    }

    @Test
    void isValid_returnsTrue_forFreshlyGeneratedToken() {

        // Arrange
        String email = "dakshkant8@gmail.com";
        UUID userId = UUID.fromString("87492bba-28ba-4850-83fe-cee99fad11be");

        // Act
        String token = underTest.generateToken(email, userId);

        // Assert
        assertTrue(underTest.isValid(token));
    }

    @Test
    void isValid_returnsFalse_forExpiredToken() {

        // Arrange
        ReflectionTestUtils.setField(underTest, "expirationMs", -1000L);
        String email = "dakshkant8@gmail.com";
        UUID userId = UUID.fromString("87492bba-28ba-4850-83fe-cee99fad11be");

        // Act
        String token = underTest.generateToken(email, userId);

        // Assert
        assertFalse(underTest.isValid(token));
    }
}
