package com.example.relay.deliveryengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "relay.delivery")
@Component
public class DeliveryListenerProperties {

    private int consumerConcurrency = 4;

    private int prefetchCount = 10;
}
