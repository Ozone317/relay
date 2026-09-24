package com.example.relay.deliveryengine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import com.example.relay.deliveryengine.destination.HostAddressLookup;
import com.example.relay.deliveryengine.destination.PolicyEnforcingDnsResolver;
import com.example.relay.deliveryengine.destination.PublicDestinationAddressPolicy;
import com.example.relay.deliveryengine.destination.SpecialPurposeAddressCatalog;
import com.example.relay.deliveryengine.http.DeliveryDeadline;
import com.example.relay.deliveryengine.http.DeliveryDeadlineContext;
import com.example.relay.deliveryengine.http.BoundedApacheResponseBodyConsumer;

class DeliveryHttpClientSecurityConfigTest {

    @Test
    void productionApacheResponseConsumerIsBoundedAndApacheAware() {
        assertTrue(new DeliveryHttpClientConfig().deliveryResponseBodyConsumer()
                instanceof BoundedApacheResponseBodyConsumer);
    }

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

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void productionApacheClientExecutesHostnameRouteWithoutSystemProxy() throws Exception {
        try (ServerSocket destination = new ServerSocket(0, 8, safeIpv4());
                ServerSocket proxyTrap = new ServerSocket(0, 8, safeIpv4())) {
            CountDownLatch destinationAccepted = new CountDownLatch(1);
            CountDownLatch destinationRequest = new CountDownLatch(1);
            AtomicInteger proxyAccepted = new AtomicInteger();
            int destinationPort = destination.getLocalPort();
            Thread destinationThread = Thread.startVirtualThread(() -> serveOnce(destination, destinationAccepted,
                    destinationRequest));
            Thread proxyThread = Thread.startVirtualThread(() -> acceptAndCount(proxyTrap, proxyAccepted));

            String oldProxyHost = System.getProperty("http.proxyHost");
            String oldProxyPort = System.getProperty("http.proxyPort");
            System.setProperty("http.proxyHost", "127.0.0.1");
            System.setProperty("http.proxyPort", Integer.toString(proxyTrap.getLocalPort()));
            try {
                DeliveryHttpClientConfig config = new DeliveryHttpClientConfig();
                PolicyEnforcingDnsResolver resolver = resolverFor("127.0.0.1");
                PoolingHttpClientConnectionManager manager = config.deliveryConnectionManager(resolver);
                try (CloseableHttpClient client = config.deliveryApacheHttpClient(manager)) {
                    HttpGet request = new HttpGet(
                            "http://localhost.localdomain:" + destinationPort + "/production-route");
                    try (DeliveryDeadlineContext.Scope ignored = DeliveryDeadlineContext.open(
                            DeliveryDeadline.start(Duration.ofSeconds(5), System::nanoTime));
                            ClassicHttpResponse response = client.execute(request)) {
                        assertEquals(200, response.getCode());
                        EntityUtils.consume(response.getEntity());
                    }
                } finally {
                    manager.close();
                }
                assertTrue(destinationAccepted.await(5, TimeUnit.SECONDS));
                assertTrue(destinationRequest.await(5, TimeUnit.SECONDS));
                assertEquals(0, proxyAccepted.get());
            } finally {
                restoreProperty("http.proxyHost", oldProxyHost);
                restoreProperty("http.proxyPort", oldProxyPort);
                destination.close();
                proxyTrap.close();
                destinationThread.join(1_000);
                proxyThread.join(1_000);
            }
        }
    }

    private static void serveOnce(ServerSocket server, CountDownLatch accepted, CountDownLatch request) {
        try (Socket socket = server.accept(); BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                OutputStream output = socket.getOutputStream()) {
            accepted.countDown();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // Read through the complete request headers before responding.
            }
            request.countDown();
            output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                    .getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (Exception ignored) {
            // Socket close during test teardown is expected.
        }
    }

    private static void acceptAndCount(ServerSocket server, AtomicInteger accepted) {
        try (Socket ignored = server.accept()) {
            accepted.incrementAndGet();
        } catch (Exception ignored) {
            // Socket close during test teardown is expected.
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
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

    private static InetAddress safeIpv4() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (java.net.UnknownHostException exception) {
            throw new AssertionError("fixed test address must be valid", exception);
        }
    }
}
