package com.example.relay.deliveryengine.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class DeliveryDnsPropertiesValidationTest {

    @Test
    void positiveValuesValidate() {
        DeliveryDnsProperties properties = new DeliveryDnsProperties();
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void zeroAndNegativeConcurrencyFailValidation() {
        DeliveryDnsProperties zero = new DeliveryDnsProperties();
        zero.setMaxConcurrency(0);
        assertThrows(IllegalStateException.class, zero::validate);

        DeliveryDnsProperties negative = new DeliveryDnsProperties();
        negative.setMaxConcurrency(-1);
        assertThrows(IllegalStateException.class, negative::validate);
    }

    @Test
    void zeroAndNegativeQueueCapacityFailValidation() {
        DeliveryDnsProperties zero = new DeliveryDnsProperties();
        zero.setQueueCapacity(0);
        assertThrows(IllegalStateException.class, zero::validate);

        DeliveryDnsProperties negative = new DeliveryDnsProperties();
        negative.setQueueCapacity(-1);
        assertThrows(IllegalStateException.class, negative::validate);
    }

    @Test
    void nullZeroAndNegativeTimeoutFailValidation() {
        DeliveryDnsProperties nullTimeout = new DeliveryDnsProperties();
        nullTimeout.setTimeout(null);
        assertThrows(IllegalStateException.class, nullTimeout::validate);

        DeliveryDnsProperties zero = new DeliveryDnsProperties();
        zero.setTimeout(Duration.ZERO);
        assertThrows(IllegalStateException.class, zero::validate);

        DeliveryDnsProperties negative = new DeliveryDnsProperties();
        negative.setTimeout(Duration.ofMillis(-1));
        assertThrows(IllegalStateException.class, negative::validate);
    }

    @Test
    void invalidConfigurationFailsApplicationStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(
                        "relay.delivery.dns.max-concurrency=0",
                        "relay.delivery.dns.queue-capacity=1",
                        "relay.delivery.dns.timeout=1s")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DeliveryDnsProperties.class)
    static class PropertiesConfiguration {
    }
}
