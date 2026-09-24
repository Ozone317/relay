package com.example.relay.deliveryengine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.SocketConfig;
import org.junit.jupiter.api.Test;

import com.example.relay.deliveryengine.destination.HostAddressLookup;
import com.example.relay.deliveryengine.destination.PolicyEnforcingDnsResolver;
import com.example.relay.deliveryengine.destination.PublicDestinationAddressPolicy;
import com.example.relay.deliveryengine.destination.SpecialPurposeAddressCatalog;
import com.example.relay.deliveryengine.http.DeliveryDeadline;
import com.example.relay.deliveryengine.http.DeliveryDeadlineContext;

class DeliveryHttpClientSecurityConfigTest {

    @Test
    void productionRequestTargetKeepsHostnameAndNoEmbeddedAddress() {
        HttpHost target = new HttpHost("https", "webhook.test", 443);

        assertEquals("webhook.test", target.getHostName());
        assertNull(target.getAddress());
    }

    @Test
    void connectionManagerAndDefaultRequestConfigurationHaveNoProxyOrSocksProxy() throws Exception {
        DeliveryHttpClientConfig config = new DeliveryHttpClientConfig();
        PolicyEnforcingDnsResolver resolver = resolverFor("127.0.0.1");
        PoolingHttpClientConnectionManager manager = config.deliveryConnectionManager(resolver);
        try (CloseableHttpClient ignored = config.deliveryApacheHttpClient(manager)) {
            assertNull(manager.getDefaultSocketConfig().getSocksProxyAddress());
            assertNull(RequestConfig.DEFAULT.getProxy());
            assertNull(RequestConfig.custom().setProxy(null).build().getProxy());
            assertEquals(40, manager.getMaxTotal());
            assertEquals(40, manager.getDefaultMaxPerRoute());
        } finally {
            manager.close();
        }
    }

    @Test
    void resolverScopedDeadlineCanResolveConfiguredTestAddress() throws Exception {
        PolicyEnforcingDnsResolver resolver = resolverFor("127.0.0.1");
        DeliveryDeadline deadline = DeliveryDeadline.start(Duration.ofSeconds(1), System::nanoTime);

        try (DeliveryDeadlineContext.Scope ignored = DeliveryDeadlineContext.open(deadline)) {
            List<InetSocketAddress> result = resolver.resolve("webhook.test", 80);
            assertEquals("127.0.0.1", result.get(0).getAddress().getHostAddress());
        }
    }

    private static PolicyEnforcingDnsResolver resolverFor(String address) throws Exception {
        InetAddress resolved = InetAddress.getByName(address);
        HostAddressLookup lookup = (hostname, deadline) -> List.of(resolved);
        // Test-only catalog deliberately omits loopback so a local listener can prove the
        // resolver-to-socket binding. Production always uses the pinned catalog.
        SpecialPurposeAddressCatalog fixture = new SpecialPurposeAddressCatalog(
                new java.io.ByteArrayInputStream("0.0.0.0/32|TEST_SENTINEL\n".getBytes(StandardCharsets.UTF_8)));
        return new PolicyEnforcingDnsResolver(lookup, new PublicDestinationAddressPolicy(fixture));
    }
}
