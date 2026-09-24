package com.example.relay.deliveryengine.config;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class DeliveryDnsPropertiesConfigurationKeysTest {

    @Test
    void applicationPropertiesExposeOnlyCanonicalDnsKeys() throws Exception {
        String properties = Files.readString(Path.of("src/main/resources/application.properties"));
        assertTrue(properties.contains("relay.delivery.dns.max-concurrency="));
        assertTrue(properties.contains("relay.delivery.dns.queue-capacity="));
        assertTrue(properties.contains("relay.delivery.dns.timeout="));
        assertFalse(properties.contains("relay.delivery.dns.maxConcurrency="));
        assertFalse(properties.contains("relay.delivery.dns.queueCapacity="));
        assertFalse(properties.contains("relay.dns."));
    }
}
