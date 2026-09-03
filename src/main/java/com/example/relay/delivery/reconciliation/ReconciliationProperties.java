package com.example.relay.delivery.reconciliation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.Data;

@Data
@ConfigurationProperties(prefix = "relay.reconciliation")
@Component
public class ReconciliationProperties {

    private Duration interval = Duration.ofSeconds(30);

    private Duration createdGrace = Duration.ofSeconds(60);

    private Duration inFlightGrace = Duration.ofSeconds(90);

    private int batchSize = 100;

    private Duration scheduledSlack = Duration.ofMinutes(5);

    @PostConstruct
    void validate() {
        if (createdGrace.compareTo(interval) < 0) {
            throw new IllegalStateException(
                    "relay.reconciliation.created-grace (" + createdGrace + ") must be >= "
                            + "relay.reconciliation.interval (" + interval + "); otherwise a genuinely "
                            + "stuck CREATED attempt is republished on every single sweep tick instead "
                            + "of once per grace period");
        }
    }

}
