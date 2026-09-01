package com.example.relay.delivery.signing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HmacSignerTest {

    private final HmacSigner underTest = new HmacSigner();

    @Test
    void sign_matchesIndependentlyComputedVector() {
        String signature = underTest.sign("test-relay-id", 1700000000L, "{\"hello\":\"world\"}", "whsec_test123");

        assertEquals("v1,EHEAXlqp8mrY4yuTUcF52UXJOJJ2hVN1W7xPO07kbmE=", signature);
    }

    @Test
    void sign_startsWithV1Prefix() {
        String signature = underTest.sign("id", 1L, "body", "secret");

        assertTrue(signature.startsWith("v1,"));
    }

    @Test
    void sign_differsWhenBodyDiffers() {
        String signatureA = underTest.sign("id", 1L, "body-a", "secret");
        String signatureB = underTest.sign("id", 1L, "body-b", "secret");

        assertNotEquals(signatureA, signatureB);
    }

    @Test
    void sign_differsWhenSecretDiffers() {
        String signatureA = underTest.sign("id", 1L, "body", "secret-a");
        String signatureB = underTest.sign("id", 1L, "body", "secret-b");

        assertNotEquals(signatureA, signatureB);
    }
}