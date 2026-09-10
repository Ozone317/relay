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

    @Test
    void buildHttpClient_hasAVirtualThreadExecutor() {
        HttpClient httpClient = new DeliveryHttpClientConfig().buildHttpClient();

        assertTrue(httpClient.executor().isPresent(),
                "expected an explicit virtual-thread executor to be configured on the HttpClient - "
                        + "without one, JdkClientHttpRequestFactory falls back to a fresh unpooled "
                        + "platform thread per request (SimpleAsyncTaskExecutor) and the JDK HttpClient "
                        + "spins up its own unbounded platform-thread pool, defeating the point of the "
                        + "virtual-thread delivery redesign");
    }
}
