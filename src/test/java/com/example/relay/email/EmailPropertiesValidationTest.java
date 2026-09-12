package com.example.relay.email;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EmailPropertiesValidationTest {

    @Test
    void validate_throws_whenSenderEmailIsBlank() {
        EmailProperties properties = new EmailProperties();
        properties.setSenderEmail("");
        properties.setSenderName("Relay");
        properties.getBrevo().setApiKey("key");

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void validate_throws_whenSenderNameIsBlank() {
        EmailProperties properties = new EmailProperties();
        properties.setSenderEmail("noreply@relay.test");
        properties.setSenderName(null);
        properties.getBrevo().setApiKey("key");

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void validate_throws_whenBrevoApiKeyIsBlank() {
        EmailProperties properties = new EmailProperties();
        properties.setSenderEmail("noreply@relay.test");
        properties.setSenderName("Relay");
        properties.getBrevo().setApiKey("   ");

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void validate_doesNotThrow_whenEverythingIsSet() {
        EmailProperties properties = new EmailProperties();
        properties.setSenderEmail("noreply@relay.test");
        properties.setSenderName("Relay");
        properties.getBrevo().setApiKey("key");

        properties.validate();
    }
}
