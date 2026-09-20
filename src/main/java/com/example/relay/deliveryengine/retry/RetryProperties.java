package com.example.relay.deliveryengine.retry;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "relay.retry")
public class RetryProperties {

    private double jitterFactor = 0.25d;
    private boolean schedulingEnabled = true;
    private Duration schedulerInterval = Duration.ofSeconds(1);
    private int schedulerBatchSize = 100;
    private Duration dispatcherInterval = Duration.ofSeconds(1);
    private int dispatcherBatchSize = 100;
    private Duration unconfirmedReadyGrace = Duration.ofSeconds(10);
    private Duration publishConfirmTimeout = Duration.ofSeconds(5);

    @PostConstruct
    public void validate() {
        if (!Double.isFinite(jitterFactor) || jitterFactor < 0) {
            throw new IllegalStateException("relay.retry.jitter-factor must be >= 0");
        }
        requirePositive("scheduler-interval", schedulerInterval);
        requirePositive("scheduler-batch-size", schedulerBatchSize);
        requirePositive("dispatcher-interval", dispatcherInterval);
        requirePositive("dispatcher-batch-size", dispatcherBatchSize);
        requireNonNegative("unconfirmed-ready-grace", unconfirmedReadyGrace);
        requirePositive("publish-confirm-timeout", publishConfirmTimeout);
        if (publishConfirmTimeout.compareTo(unconfirmedReadyGrace) >= 0) {
            throw new IllegalStateException(
                    "relay.retry.publish-confirm-timeout (" + publishConfirmTimeout + ") must be < "
                            + "relay.retry.unconfirmed-ready-grace (" + unconfirmedReadyGrace + ")");
        }
    }

    private void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("relay.retry." + name + " must be > 0");
        }
    }

    private void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalStateException("relay.retry." + name + " must be > 0");
        }
    }

    private void requireNonNegative(String name, Duration value) {
        if (value == null || value.isNegative()) {
            throw new IllegalStateException("relay.retry." + name + " must be >= 0");
        }
    }
}
