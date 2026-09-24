package com.example.relay.deliveryengine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class DeliveryDnsPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultsBindFromConfigurationProperties() {
        contextRunner.run(context -> {
            DeliveryDnsProperties properties = context.getBean(DeliveryDnsProperties.class);
            assertEquals(40, properties.getMaxConcurrency());
            assertEquals(40, properties.getQueueCapacity());
            assertEquals(Duration.ofSeconds(3), properties.getTimeout());
        });
    }

    @Test
    void explicitOverridesBindFromCanonicalKeys() {
        contextRunner.withPropertyValues(
                "relay.delivery.dns.max-concurrency=3",
                "relay.delivery.dns.queue-capacity=7",
                "relay.delivery.dns.timeout=1250ms")
                .run(context -> {
                    DeliveryDnsProperties properties = context.getBean(DeliveryDnsProperties.class);
                    assertEquals(3, properties.getMaxConcurrency());
                    assertEquals(7, properties.getQueueCapacity());
                    assertEquals(Duration.ofMillis(1250), properties.getTimeout());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DeliveryDnsProperties.class)
    static class PropertiesConfiguration {
    }
}
