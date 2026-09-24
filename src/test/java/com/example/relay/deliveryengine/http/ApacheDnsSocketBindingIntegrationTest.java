package com.example.relay.deliveryengine.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.TimeValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import com.example.relay.deliveryengine.config.DeliveryHttpClientConfig;
import com.example.relay.deliveryengine.destination.HostAddressLookup;
import com.example.relay.deliveryengine.destination.PolicyEnforcingDnsResolver;
import com.example.relay.deliveryengine.destination.PublicDestinationAddressPolicy;
import com.example.relay.deliveryengine.destination.SpecialPurposeAddressCatalog;

class ApacheDnsSocketBindingIntegrationTest {

    private static final String TARGET_HOST = "webhook.invalid";

    private final ExecutorService servers = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown() {
        servers.close();
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void bindsOnlyToResolverReturnedAddressAndReusesValidatedSocketDespiteProxyProperties() throws Exception {
        try (HttpFixture destination = HttpFixture.keepAlive(2, safeIpv4());
                HttpFixture destinationTrap = HttpFixture.keepAlive(1, ipv6Loopback(), destination.port());
                HttpFixture proxy = HttpFixture.keepAlive(1, safeIpv4())) {
            String oldProxyHost = System.getProperty("http.proxyHost");
            String oldProxyPort = System.getProperty("http.proxyPort");
            System.setProperty("http.proxyHost", "127.0.0.1");
            System.setProperty("http.proxyPort", Integer.toString(proxy.port()));
            try {
                // Properties must be installed before the production client is built: this is the
                // only point at which a useSystemProperties mutation can capture them.
                try (CloseableHttpClient client = clientFor(destination, true)) {
                    ClassicHttpResponse first = execute(client, new HttpGet(uri(destination, "/one")));
                    EntityUtils.consume(first.getEntity());
                    first.close();
                    ClassicHttpResponse second = execute(client, new HttpGet(uri(destination, "/two")));
                    EntityUtils.consume(second.getEntity());
                    second.close();

                    assertTrue(destination.requests.await(5, TimeUnit.SECONDS));
                    assertEquals(1, destination.acceptedSockets.get());
                    assertEquals(2, destination.requestsSeen.get());
                    assertEquals(List.of(TARGET_HOST + ":" + destination.port(), TARGET_HOST + ":" + destination.port()),
                            destination.hostHeaders);
                    assertEquals(0, destinationTrap.acceptedSockets.get());
                    assertEquals(0, proxy.acceptedSockets.get());
                    assertEquals(1, destination.resolverCalls.get());
                }
            } finally {
                restoreProperty("http.proxyHost", oldProxyHost);
                restoreProperty("http.proxyPort", oldProxyPort);
            }
        }
    }

    @Test
    void returnsRedirectWithoutFollowingTrapLocation() throws Exception {
        try (HttpFixture trap = HttpFixture.keepAlive(1, safeIpv4());
                HttpFixture destination = HttpFixture.redirect(trap.port(), safeIpv4());
                CloseableHttpClient client = clientFor(destination, false)) {
            ClassicHttpResponse response = execute(client, new HttpGet(uri(destination, "/redirect")));
            try {
                assertEquals(302, response.getCode());
                assertEquals("http://trap.test:" + trap.port() + "/trap",
                        response.getFirstHeader("Location").getValue());
            } finally {
                response.close();
            }
            assertEquals(0, trap.acceptedSockets.get());
            assertEquals(1, destination.resolverCalls.get());
        }
    }

    @Test
    void doesNotAutomaticallyRetryDroppedPost() throws Exception {
        try (HttpFixture destination = HttpFixture.dropPost();
                CloseableHttpClient client = clientFor(destination, false)) {
            HttpPost post = new HttpPost(uri(destination, "/drop"));
            post.setEntity(new StringEntity("signed-body", ContentType.APPLICATION_JSON));

            assertThrows(IOException.class, () -> execute(client, post));
            assertTrue(destination.requests.await(5, TimeUnit.SECONDS));
            Thread.sleep(250);
            assertEquals(1, destination.acceptedSockets.get());
            assertEquals(1, destination.requestsSeen.get());
            assertEquals(1, destination.resolverCalls.get());
        }
    }

    @Test
    void doesNotAutomaticallyRetryDroppedIdempotentGet() throws Exception {
        try (HttpFixture destination = HttpFixture.dropGet();
                CloseableHttpClient client = clientFor(destination, false)) {
            assertThrows(IOException.class, () -> execute(client, new HttpGet(uri(destination, "/drop-get"))));
            assertTrue(destination.requests.await(5, TimeUnit.SECONDS));
            Thread.sleep(250);
            assertEquals(1, destination.acceptedSockets.get());
            assertEquals(1, destination.requestsSeen.get());
            assertEquals(1, destination.resolverCalls.get());
        }
    }

    @Test
    void portAwareResolverWinsOverLegacyResolverTrap() throws Exception {
        try (HttpFixture destination = HttpFixture.keepAlive(1, safeIpv4());
                HttpFixture legacyTrap = HttpFixture.keepAlive(1, ipv6Loopback(), destination.port());
                CloseableHttpClient client = clientFor(legacyFallbackProbe(destination))) {
            ClassicHttpResponse response = execute(client, new HttpGet(uri(destination, "/port-aware")));
            EntityUtils.consume(response.getEntity());
            response.close();
            assertTrue(destination.requests.await(5, TimeUnit.SECONDS));
            assertEquals(1, destination.acceptedSockets.get());
            assertEquals(0, legacyTrap.acceptedSockets.get());
            assertEquals(1, destination.resolverCalls.get());
        }
    }

    private CloseableHttpClient clientFor(HttpFixture destination, boolean failover) throws Exception {
        InetAddress unavailable = ipv4(127, 0, 0, 2);
        InetAddress permitted = safeIpv4();
        HostAddressLookup lookup = (hostname, deadline) -> {
            if (failover) {
                return List.of(unavailable, permitted);
            }
            return List.of(permitted);
        };
        HostAddressLookup countedLookup = (hostname, deadline) -> {
            destination.resolverCalls.incrementAndGet();
            return lookup.lookup(hostname, deadline);
        };
        PolicyEnforcingDnsResolver resolver = new PolicyEnforcingDnsResolver(countedLookup,
                testPolicyAllowingLoopback());
        DeliveryHttpClientConfig config = new DeliveryHttpClientConfig();
        PoolingHttpClientConnectionManager manager = config.deliveryConnectionManager(resolver);
        return config.deliveryApacheHttpClient(manager);
    }

    private CloseableHttpClient clientFor(DnsResolver resolver) throws Exception {
        DeliveryHttpClientConfig config = new DeliveryHttpClientConfig();
        PoolingHttpClientConnectionManager manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setTlsSocketStrategy(DefaultClientTlsStrategy.createDefault())
                .setDefaultSocketConfig(SocketConfig.custom().setSocksProxyAddress(null).build())
                .setSchemePortResolver(DefaultSchemePortResolver.INSTANCE)
                .setMaxConnTotal(40)
                .setMaxConnPerRoute(40)
                .setConnectionTimeToLive(TimeValue.ofMinutes(5))
                .build();
        return config.deliveryApacheHttpClient(manager);
    }

    private static DnsResolver legacyFallbackProbe(HttpFixture destination) throws Exception {
        InetAddress trapAddress = ipv6Loopback();
        InetAddress unavailable = ipv4(127, 0, 0, 2);
        HostAddressLookup lookup = (hostname, deadline) -> {
            destination.resolverCalls.incrementAndGet();
            return List.of(unavailable, safeIpv4());
        };
        PolicyEnforcingDnsResolver enforcing = new PolicyEnforcingDnsResolver(lookup,
                testPolicyAllowingLoopback());
        return new DnsResolver() {
            @Override
            public List<InetSocketAddress> resolve(String host, int port) throws java.net.UnknownHostException {
                return enforcing.resolve(host, port);
            }

            @Override
            public InetAddress[] resolve(String host) {
                return new InetAddress[] { trapAddress };
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }

    private static ClassicHttpResponse execute(CloseableHttpClient client, org.apache.hc.core5.http.ClassicHttpRequest request)
            throws IOException {
        try (DeliveryDeadlineContext.Scope ignored = DeliveryDeadlineContext.open(
                DeliveryDeadline.start(Duration.ofSeconds(5), System::nanoTime))) {
            return client.execute(request);
        }
    }

    private static String uri(HttpFixture fixture, String path) {
        return "http://" + TARGET_HOST + ":" + fixture.port() + path;
    }

    private static InetAddress safeIpv4() {
        return ipv4(127, 0, 0, 1);
    }

    private static InetAddress ipv4(int first, int second, int third, int fourth) {
        return explicitAddress(new byte[] {(byte) first, (byte) second, (byte) third, (byte) fourth});
    }

    private static InetAddress ipv6Loopback() {
        byte[] address = new byte[16];
        address[15] = 1;
        return explicitAddress(address);
    }

    private static InetAddress explicitAddress(byte[] address) {
        try {
            return InetAddress.getByAddress(address);
        } catch (java.net.UnknownHostException exception) {
            throw new AssertionError("fixed test address must be valid", exception);
        }
    }

    private static PublicDestinationAddressPolicy testPolicyAllowingLoopback() {
        SpecialPurposeAddressCatalog fixture = new SpecialPurposeAddressCatalog(new ByteArrayInputStream(
                "0.0.0.0/32|TEST_SENTINEL\n".getBytes(StandardCharsets.UTF_8)));
        return new PublicDestinationAddressPolicy(fixture);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static final class HttpFixture implements AutoCloseable {

        private final ServerSocket server;
        private final Mode mode;
        private final int expectedRequests;
        private final int redirectPort;
        private final int maxConnections;
        private final AtomicInteger acceptedSockets = new AtomicInteger();
        private final AtomicInteger requestsSeen = new AtomicInteger();
        private final AtomicInteger resolverCalls = new AtomicInteger();
        private final CountDownLatch requests;
        private final List<String> hostHeaders = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        private HttpFixture(Mode mode, int expectedRequests) throws IOException {
            this(mode, expectedRequests, -1, safeIpv4(), 1, 0);
        }

        private HttpFixture(Mode mode, int expectedRequests, int redirectPort, InetAddress bindAddress,
                int maxConnections, int port) throws IOException {
            this.server = new ServerSocket(port, 8, bindAddress);
            this.mode = mode;
            this.expectedRequests = expectedRequests;
            this.redirectPort = redirectPort;
            this.maxConnections = maxConnections;
            this.requests = new CountDownLatch(expectedRequests);
            executor.submit(this::serve);
        }

        static HttpFixture keepAlive(int expectedRequests) throws IOException {
            return keepAlive(expectedRequests, safeIpv4());
        }

        static HttpFixture keepAlive(int expectedRequests, InetAddress bindAddress) throws IOException {
            return keepAlive(expectedRequests, bindAddress, 0);
        }

        static HttpFixture keepAlive(int expectedRequests, InetAddress bindAddress, int port) throws IOException {
            return new HttpFixture(Mode.OK, expectedRequests, -1, bindAddress, expectedRequests, port);
        }

        static HttpFixture dropPost() throws IOException {
            return new HttpFixture(Mode.DROP, 1, -1, safeIpv4(), 4, 0);
        }

        static HttpFixture dropGet() throws IOException {
            return new HttpFixture(Mode.DROP, 1, -1, safeIpv4(), 4, 0);
        }

        static HttpFixture redirect(int trapPort, InetAddress bindAddress) throws IOException {
            return new HttpFixture(Mode.REDIRECT, 1, trapPort, bindAddress, 1, 0);
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            try {
                while (!server.isClosed() && acceptedSockets.get() < maxConnections) {
                    Socket socket = server.accept();
                    acceptedSockets.incrementAndGet();
                    executor.submit(() -> serveSocket(socket));
                }
            } catch (IOException ignored) {
                // Closing the fixture interrupts accept.
            }
        }

        private void serveSocket(Socket socket) {
            try (socket; BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    OutputStream output = socket.getOutputStream()) {
                while (!socket.isClosed()) {
                    String requestLine = reader.readLine();
                    if (requestLine == null) {
                        return;
                    }
                    if (requestLine.isEmpty()) {
                        continue;
                    }
                    Map<String, String> headers = new java.util.HashMap<>();
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        int colon = line.indexOf(':');
                        if (colon > 0) {
                            headers.put(line.substring(0, colon).toLowerCase(), line.substring(colon + 1).trim());
                        }
                    }
                    int bodyLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                    for (int i = 0; i < bodyLength; i++) {
                        if (reader.read() < 0) {
                            return;
                        }
                    }
                    String host = headers.get("host");
                    if (host != null) {
                        hostHeaders.add(host);
                    }
                    int requestNumber = requestsSeen.incrementAndGet();
                    requests.countDown();
                    if (mode == Mode.DROP) {
                        return;
                    }
                    if (mode == Mode.REDIRECT) {
                        write(output, "HTTP/1.1 302 Found\r\nLocation: http://trap.test:" + redirectPort + "/trap\r\n"
                                + "Content-Length: 0\r\nConnection: close\r\n\r\n");
                        return;
                    }
                    write(output, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok");
                    if (requestNumber >= expectedRequests) {
                        return;
                    }
                }
            } catch (IOException ignored) {
                // The dropped-POST fixture intentionally closes without a response.
            }
        }

        private static void write(OutputStream output, String response) throws IOException {
            output.write(response.getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }

        @Override
        public void close() throws IOException {
            server.close();
            executor.close();
        }

        private enum Mode {
            OK, REDIRECT, DROP
        }
    }
}
