package com.example.relay.email;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.relay.support.SharedPostgresContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {"relay.email.sender-email=test-sender@example.test",
        "relay.email.sender-name=Test Sender", "relay.email.brevo.api-key=test-key-123"})
class EmailPropertiesBindingTest implements SharedPostgresContainer {

    @Autowired
    private EmailProperties emailProperties;

    @Test
    void bindsEveryConfiguredKeyOntoTheMatchingField() {
        assertEquals("test-sender@example.test", emailProperties.getSenderEmail());
        assertEquals("Test Sender", emailProperties.getSenderName());
        assertEquals("test-key-123", emailProperties.getBrevo().getApiKey());
    }
}
