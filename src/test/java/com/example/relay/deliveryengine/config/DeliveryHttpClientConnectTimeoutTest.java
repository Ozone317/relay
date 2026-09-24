package com.example.relay.deliveryengine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.Test;

import com.example.relay.deliveryengine.destination.PolicyEnforcingDnsResolver;
import com.example.relay.deliveryengine.destination.PublicDestinationAddressPolicy;

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

    @Test
    void apacheConnectionManager_hasBoundedCapacityForFortyWorkers() throws Exception {
        PolicyEnforcingDnsResolver resolver = new PolicyEnforcingDnsResolver(
                (hostname, deadline) -> List.of(InetAddress.getLoopbackAddress()),
                new PublicDestinationAddressPolicy());
        PoolingHttpClientConnectionManager manager = new DeliveryHttpClientConfig()
                .deliveryConnectionManager(resolver);
        try {
            assertEquals(40, manager.getMaxTotal());
            assertEquals(40, manager.getDefaultMaxPerRoute());
            assertTrue(manager.getDefaultSocketConfig().getSocksProxyAddress() == null);
        } finally {
            manager.close();
        }
    }
}
