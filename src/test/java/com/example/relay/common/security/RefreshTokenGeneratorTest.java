package com.example.relay.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RefreshTokenGeneratorTest {

    private RefreshTokenGenerator underTest;

    @BeforeEach
    void setUp() {
        underTest = new RefreshTokenGenerator();
    }

    @Test
    void generateRawToken_returns64HexCharacters() {
        String token = underTest.generateRawToken();

        assertEquals(64, token.length());
        assertTrue(token.matches("[0-9a-f]{64}"));
    }

    @Test
    void generateRawToken_neverRepeats() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(seen.add(underTest.generateRawToken()), "generated a duplicate token");
        }
    }

    @Test
    void hash_isStableForTheSameInput() {
        String token = underTest.generateRawToken();

        assertEquals(underTest.hash(token), underTest.hash(token));
    }

    @Test
    void hash_differsForDifferentInputs() {
        assertNotEquals(underTest.hash("a"), underTest.hash("b"));
    }

    @Test
    void hash_returns64HexCharactersAndNeverTheInputItself() {
        String token = underTest.generateRawToken();
        String hashed = underTest.hash(token);

        assertEquals(64, hashed.length());
        assertTrue(hashed.matches("[0-9a-f]{64}"));
        assertNotEquals(token, hashed);
    }
}
