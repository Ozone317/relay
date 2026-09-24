package com.example.relay.endpoint.api.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WebhookUrlValidatorTest {

    private final WebhookUrlValidator validator = new WebhookUrlValidator();

    @Test
    void isValid_acceptsSemanticWebhookUrl() {
        assertTrue(validator.isValid("https://example.com/hook", null));
    }

    @Test
    void isValid_acceptsNullForNullableUpdateField() {
        assertTrue(validator.isValid(null, null));
    }

    @Test
    void isValid_rejectsAmbiguousNumericHost() {
        assertFalse(validator.isValid("http://2130706433/", null));
    }

    @Test
    void isValid_rejectsMalformedPort() {
        assertFalse(validator.isValid("http://example.com:65536/", null));
    }

    @Test
    void isValid_rejectsProtectedLiteralAddresses() {
        assertFalse(validator.isValid("http://127.0.0.1/", null));
        assertFalse(validator.isValid("http://[::1]/", null));
        assertFalse(validator.isValid("http://192.168.1.10/", null));
        assertFalse(validator.isValid("http://[fc00::1]/", null));
        assertFalse(validator.isValid("http://[fe80::1]/", null));
        assertFalse(validator.isValid("http://[::ffff:127.0.0.1]/", null));
    }

    @Test
    void isValid_doesNotResolveDnsNames() {
        assertTrue(validator.isValid("https://deliberately-nonexistent.example.invalid/hook", null));
    }
}
