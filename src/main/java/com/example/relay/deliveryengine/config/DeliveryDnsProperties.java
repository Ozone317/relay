package com.example.relay.deliveryengine.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "relay.delivery.dns")
public class DeliveryDnsProperties {

    private int maxConcurrency = 40;

    private int queueCapacity = 40;

    private Duration timeout = Duration.ofSeconds(3);

    @PostConstruct
    public void validate() {
        if (maxConcurrency <= 0) {
            throw new IllegalStateException("relay.delivery.dns.max-concurrency must be greater than zero");
        }
        if (queueCapacity <= 0) {
            throw new IllegalStateException("relay.delivery.dns.queue-capacity must be greater than zero");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("relay.delivery.dns.timeout must be strictly positive");
        }
    }
}
