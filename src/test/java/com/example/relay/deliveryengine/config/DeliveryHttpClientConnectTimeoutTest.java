package com.example.relay.deliveryengine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DeliveryHttpClientConnectTimeoutTest {

    @Test
    void buildHttpClient_hasTheConfigured15sConnectTimeout() {
        HttpClient httpClient = new DeliveryHttpClientConfig().buildHttpClient();

        assertTrue(httpClient.connectTimeout().isPresent(),
                "expected an explicit connect timeout to be configured, not left to the JDK default "
                        + "(which is none) - a default-built HttpClient never times out on connect at all");
        assertEquals(Duration.ofMillis(DeliveryHttpClientConfig.DELIVERY_TIMEOUT_MILLIS),
                httpClient.connectTimeout().get());
    }
}
