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
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.relay.deliveryengine.config.DeliveryHttpClientConfig;
import com.example.relay.deliveryengine.destination.HostAddressLookup;
import com.example.relay.deliveryengine.destination.PolicyEnforcingDnsResolver;
import com.example.relay.deliveryengine.destination.PublicDestinationAddressPolicy;
import com.example.relay.deliveryengine.destination.SpecialPurposeAddressCatalog;

class ApacheDnsSocketBindingIntegrationTest {

    private final ExecutorService servers = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown() {
        servers.close();
    }

    @Test
    void bindsOnlyToResolverReturnedAddressAndReusesValidatedSocketDespiteProxyProperties() throws Exception {
        try (HttpFixture destination = HttpFixture.keepAlive(2); HttpFixture proxy = HttpFixture.keepAlive(1);
                CloseableHttpClient client = clientFor(destination, true)) {
            String oldProxyHost = System.getProperty("http.proxyHost");
            String oldProxyPort = System.getProperty("http.proxyPort");
            System.setProperty("http.proxyHost", "127.0.0.1");
            System.setProperty("http.proxyPort", Integer.toString(proxy.port()));
            try {
                ClassicHttpResponse first = execute(client, new HttpGet(uri(destination, "/one")));
                EntityUtils.consume(first.getEntity());
                first.close();
                ClassicHttpResponse second = execute(client, new HttpGet(uri(destination, "/two")));
                EntityUtils.consume(second.getEntity());
                second.close();

                assertTrue(destination.requests.await(5, TimeUnit.SECONDS));
                assertEquals(1, destination.acceptedSockets.get());
                assertEquals(2, destination.requestsSeen.get());
                assertEquals("webhook.test:" + destination.port(), destination.hostHeaders.get(0));
                assertEquals(0, proxy.acceptedSockets.get());
                assertEquals(1, destination.resolverCalls.get());
            } finally {
                restoreProperty("http.proxyHost", oldProxyHost);
                restoreProperty("http.proxyPort", oldProxyPort);
            }
        }
    }

    @Test
    void returnsRedirectWithoutFollowingTrapLocation() throws Exception {
        try (HttpFixture trap = HttpFixture.keepAlive(1); HttpFixture destination = HttpFixture.redirect(trap.port());
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
            assertEquals(1, destination.acceptedSockets.get());
            assertEquals(1, destination.requestsSeen.get());
            assertEquals(1, destination.resolverCalls.get());
        }
    }

    private CloseableHttpClient clientFor(HttpFixture destination, boolean failover) throws Exception {
        InetAddress unavailable = InetAddress.getByName("127.0.0.2");
        InetAddress permitted = InetAddress.getByName("127.0.0.1");
        HostAddressLookup lookup = (hostname, deadline) -> {
            if (failover) {
                return List.of(unavailable, permitted);
            }
            return List.of(permitted);
        };
        PolicyEnforcingDnsResolver resolver = new PolicyEnforcingDnsResolver(lookup,
                testPolicyAllowingLoopback());
        HostAddressLookup countedLookup = (hostname, deadline) -> {
            destination.resolverCalls.incrementAndGet();
            return lookup.lookup(hostname, deadline);
        };
        resolver = new PolicyEnforcingDnsResolver(countedLookup, testPolicyAllowingLoopback());
        DeliveryHttpClientConfig config = new DeliveryHttpClientConfig();
        PoolingHttpClientConnectionManager manager = config.deliveryConnectionManager(resolver);
        return config.deliveryApacheHttpClient(manager);
    }

    private static ClassicHttpResponse execute(CloseableHttpClient client, org.apache.hc.core5.http.ClassicHttpRequest request)
            throws IOException {
        try (DeliveryDeadlineContext.Scope ignored = DeliveryDeadlineContext.open(
                DeliveryDeadline.start(Duration.ofSeconds(5), System::nanoTime))) {
            return client.execute(request);
        }
    }

    private static String uri(HttpFixture fixture, String path) {
        return "http://webhook.test:" + fixture.port() + path;
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
        private final AtomicInteger acceptedSockets = new AtomicInteger();
        private final AtomicInteger requestsSeen = new AtomicInteger();
        private final AtomicInteger resolverCalls = new AtomicInteger();
        private final CountDownLatch requests;
        private final List<String> hostHeaders = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        private HttpFixture(Mode mode, int expectedRequests) throws IOException {
            this(mode, expectedRequests, -1);
        }

        private HttpFixture(Mode mode, int expectedRequests, int redirectPort) throws IOException {
            this.server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            this.mode = mode;
            this.expectedRequests = expectedRequests;
            this.redirectPort = redirectPort;
            this.requests = new CountDownLatch(expectedRequests);
            executor.submit(this::serve);
        }

        static HttpFixture keepAlive(int expectedRequests) throws IOException {
            return new HttpFixture(Mode.OK, expectedRequests);
        }

        static HttpFixture redirect(int trapPort) throws IOException {
            return new HttpFixture(Mode.REDIRECT, 1, trapPort);
        }

        static HttpFixture dropPost() throws IOException {
            return new HttpFixture(Mode.DROP, 1);
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            try {
                while (!server.isClosed() && requestsSeen.get() < expectedRequests) {
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
