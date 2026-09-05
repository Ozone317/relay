package com.example.relay.common.security;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class JwtServiceTest {

    private static final String TEST_SECRET = "test-secret-key-which-is-long-enough-for-hmac-sha-algorithms";
    private static final String OTHER_SECRET = "a-completely-different-secret-key-also-long-enough-for-hmac256";

    private JwtService underTest;

    @BeforeEach
    void setUp() {
        underTest = new JwtService(new AuthProperties(), TEST_SECRET);
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
        AuthProperties expiringImmediately = new AuthProperties();
        expiringImmediately.setAccessTokenTtl(Duration.ofMillis(1));
        JwtService shortLived = new JwtService(expiringImmediately, TEST_SECRET);
        String token = shortLived.generateToken("daksh@example.com", UUID.randomUUID());

        await().atMost(Duration.ofSeconds(2)).until(() -> !shortLived.isValid(token));
    }

    @Test
    void isValid_returnsFalse_forATokenSignedWithADifferentKey() {
        String token = new JwtService(new AuthProperties(), OTHER_SECRET)
                .generateToken("daksh@example.com", UUID.randomUUID());

        assertFalse(underTest.isValid(token));
    }

    @Test
    void isValid_returnsFalse_forAMalformedString() {
        assertFalse(underTest.isValid("not-a-jwt"));
    }

    @Test
    void isValid_returnsFalse_forATamperedPayload() {
        String token = underTest.generateToken("daksh@example.com", UUID.randomUUID());
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "XX." + parts[2];

        assertFalse(underTest.isValid(tampered));
    }

    @Test
    void isValid_returnsFalse_forAnEmptyString() {
        assertFalse(underTest.isValid(""));
    }
}
