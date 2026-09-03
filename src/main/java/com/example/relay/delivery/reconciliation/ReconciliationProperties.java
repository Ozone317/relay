package com.example.relay.delivery.reconciliation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "relay.reconciliation")
@Component
public class ReconciliationProperties {

    private Duration interval = Duration.ofSeconds(30);
    
    private Duration createdGrace = Duration.ofSeconds(10);
    
    private Duration inFlightGrace = Duration.ofSeconds(90);
    
    private int batchSize = 100;

    private Duration scheduledSlack = Duration.ofMinutes(5);

}
