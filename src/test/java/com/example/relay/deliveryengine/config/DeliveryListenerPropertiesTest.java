package com.example.relay.deliveryengine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.relay.support.SharedPostgresContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "relay.delivery.consumer-concurrency=7",
        "relay.delivery.prefetch-count=13"
})
class DeliveryListenerPropertiesTest implements SharedPostgresContainer {

    @Autowired
    private DeliveryListenerProperties properties;

    @Test
    void bindsBothPropertiesFromRelayDeliveryPrefix() {
        assertEquals(7, properties.getConsumerConcurrency());
        assertEquals(13, properties.getPrefetchCount());
    }
}
