package com.example.relay.deliveryengine.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.support.SharedPostgresContainer;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

@SpringBootTest
class DeliveryListenerPropertiesConfigurationKeysTest implements SharedPostgresContainer {

    @Test
    void everyRelayDeliveryKeyInApplicationPropertiesBindsToARealField() throws IOException {
        Properties fileProperties = new Properties();
        try (InputStream in = new ClassPathResource("application.properties").getInputStream()) {
            fileProperties.load(in);
        }

        Set<String> relayDeliveryKeys = fileProperties.keySet().stream()
                .map(Object::toString)
                .filter(key -> key.startsWith("relay.delivery."))
                .collect(Collectors.toSet());

        assertTrue(relayDeliveryKeys.contains("relay.delivery.consumer-concurrency"),
                "expected relay.delivery.consumer-concurrency in application.properties");
        assertTrue(relayDeliveryKeys.contains("relay.delivery.prefetch-count"),
                "expected relay.delivery.prefetch-count in application.properties");

        // Relaxed-binding form: kebab-case in the file maps to the camelCase field by stripping
        // non-alphanumerics and lowercasing - same check style as AuthPropertiesConfigurationKeysTest.
        Set<String> bindableFieldNames = Set.of("consumerconcurrency", "prefetchcount");
        for (String key : relayDeliveryKeys) {
            String relaxed = key.substring("relay.delivery.".length()).replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
            assertTrue(bindableFieldNames.contains(relaxed),
                    "key " + key + " does not relaxed-bind to any known DeliveryListenerProperties field");
        }
    }
}
