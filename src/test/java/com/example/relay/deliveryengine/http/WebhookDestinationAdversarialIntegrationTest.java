package com.example.relay.deliveryengine.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.deliveryengine.config.DeliveryHttpClientConfig;
import com.example.relay.deliveryengine.destination.PolicyEnforcingDnsResolver;
import com.example.relay.deliveryengine.destination.PublicDestinationAddressPolicy;
import com.example.relay.deliveryengine.destination.SpecialPurposeAddressCatalog;
import com.example.relay.deliveryengine.destination.DestinationPolicyBlockedException;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class WebhookDestinationAdversarialIntegrationTest {

    private static final String TARGET_HOST = "rebind.test";
    private final ExecutorService servers = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown() {
        servers.close();
    }

    @Test
    void protectedLiteralAndProtectedDnsAnswerOpenNoSocket() throws Exception {
        try (HttpListener trap = HttpListener.keepAlive(loopback(1), 1)) {
            try (CloseableHttpClient literalClient = client(new PublicDestinationAddressPolicy(),
                    new ControllableHostAddressLookup())) {
                assertThrows(DestinationPolicyBlockedException.class, () -> execute(literalClient,
                        "http://127.0.0.1:" + trap.port() + "/literal"));
            }
            ControllableHostAddressLookup lookup = new ControllableHostAddressLookup().enqueue(loopback(1));
            try (CloseableHttpClient dnsClient = client(new PublicDestinationAddressPolicy(), lookup)) {
                assertThrows(DestinationPolicyBlockedException.class, () -> execute(dnsClient,
                        "http://" + TARGET_HOST + ":" + trap.port() + "/dns"));
            }
            assertEquals(0, trap.acceptedSockets.get(), "policy rejection must precede accept");
            assertEquals(1, lookup.invocationCount());
            assertEquals(List.of(TARGET_HOST + "."), lookup.absoluteHostnames());
        }
    }

    @Test
    void mixedPermittedAndProtectedAnswersRejectBeforeEitherListener() throws Exception {
        try (HttpListener permitted = HttpListener.keepAlive(loopback(2), 1);
                HttpListener trap = HttpListener.keepAlive(loopback(1), 1, permitted.port())) {
            ControllableHostAddressLookup lookup = new ControllableHostAddressLookup()
                    .enqueue(loopback(2), loopback(1));
            try (CloseableHttpClient client = client(policyBlocking(loopback(1)), lookup)) {
                assertThrows(DestinationPolicyBlockedException.class, () -> execute(client,
                        "http://" + TARGET_HOST + ":" + permitted.port() + "/mixed"));
            }
            assertEquals(0, permitted.acceptedSockets.get());
            assertEquals(0, trap.acceptedSockets.get());
            assertEquals(1, lookup.invocationCount());
        }
    }

    @Test
    void unavailablePermittedCandidateFailsOverOnlyWithinExactValidatedSet() throws Exception {
        try (HttpListener permitted = HttpListener.keepAlive(loopback(2), 1);
                HttpListener protectedTrap = HttpListener.keepAlive(loopback(1), 1, permitted.port())) {
            ControllableHostAddressLookup lookup = new ControllableHostAddressLookup()
                    .enqueue(loopback(3), loopback(2));
            try (CloseableHttpClient client = client(policyBlocking(loopback(1)), lookup)) {
                try (ClassicHttpResponse response = execute(client,
                        "http://" + TARGET_HOST + ":" + permitted.port() + "/failover")) {
                    assertEquals(200, response.getCode());
                    EntityUtils.consume(response.getEntity());
                }
            }
            assertEquals(1, permitted.acceptedSockets.get());
            assertEquals(1, permitted.requestsSeen.get());
            assertEquals(loopback(2), permitted.localPeers.get(0));
            assertEquals(0, protectedTrap.acceptedSockets.get());
            assertEquals(1, lookup.invocationCount());
        }
    }

    @Test
    void secondResolutionTrapCannotRedirectOneNewConnection() throws Exception {
        try (HttpListener permitted = HttpListener.keepAlive(loopback(2), 1);
                HttpListener trap = HttpListener.keepAlive(loopback(1), 1, permitted.port())) {
            ControllableHostAddressLookup lookup = new ControllableHostAddressLookup()
                    .enqueue(loopback(2)).enqueue(loopback(1));
            try (CloseableHttpClient client = client(policyBlocking(loopback(1)), lookup)) {
                try (ClassicHttpResponse response = execute(client,
                        "http://" + TARGET_HOST + ":" + permitted.port() + "/rebind")) {
                    assertEquals(200, response.getCode());
                    EntityUtils.consume(response.getEntity());
                }
            }
            assertEquals(1, lookup.invocationCount());
            assertEquals(1, permitted.requestsSeen.get());
            assertEquals(loopback(2), permitted.localPeers.get(0));
            assertEquals(0, trap.acceptedSockets.get());
        }
    }

    @Test
    void pooledSocketReusesWithoutLookupThenReplacementClassifiesAgain() throws Exception {
        try (HttpListener permitted = HttpListener.keepAlive(loopback(2), 2);
                HttpListener trap = HttpListener.keepAlive(loopback(1), 1, permitted.port())) {
            ControllableHostAddressLookup lookup = new ControllableHostAddressLookup()
                    .enqueue(loopback(2)).enqueue(loopback(1));
            DeliveryHttpClientConfig config = new DeliveryHttpClientConfig();
            var manager = config.deliveryConnectionManager(new PolicyEnforcingDnsResolver(
                    lookup, policyBlocking(loopback(1))));
            try (CloseableHttpClient client = config.deliveryApacheHttpClient(manager)) {
                try (ClassicHttpResponse response = execute(client,
                        "http://" + TARGET_HOST + ":" + permitted.port() + "/one")) {
                    assertEquals(200, response.getCode());
                    EntityUtils.consume(response.getEntity());
                }
                try (ClassicHttpResponse response = execute(client,
                        "http://" + TARGET_HOST + ":" + permitted.port() + "/reuse")) {
                    assertEquals(200, response.getCode());
                    EntityUtils.consume(response.getEntity());
                }
                assertTrue(permitted.connectionClosed.await(5, TimeUnit.SECONDS));
                assertThrows(DestinationPolicyBlockedException.class, () -> execute(client,
                        "http://" + TARGET_HOST + ":" + permitted.port() + "/two"));
            }
            assertEquals(2, lookup.invocationCount());
            assertEquals(1, permitted.acceptedSockets.get());
            assertEquals(2, permitted.requestsSeen.get());
            assertEquals(loopback(2), permitted.localPeers.get(0));
            assertEquals(0, trap.acceptedSockets.get(), "protected replacement must open no socket");
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void explicitDirectRouteIgnoresHttpHttpsAndSocksSystemProxyTraps() throws Exception {
        String[] keys = {"http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort",
                "socksProxyHost", "socksProxyPort", "java.net.useSystemProxies"};
        Map<String, String> old = remember(keys);
        ProxySelector oldProxySelector = ProxySelector.getDefault();
        synchronized (ProxySelector.class) {
            try (HttpListener permitted = HttpListener.keepAlive(loopback(2), 1);
                    WebhookTlsIdentityIntegrationTest.TlsListener secure =
                            WebhookTlsIdentityIntegrationTest.TlsListener.start(
                                    "p03-webhook-test-cert.pem", "p03-webhook-test-key.pem");
                    HttpListener proxy = HttpListener.keepAlive(loopback(1), 2)) {
            System.setProperty("http.proxyHost", "127.0.0.1");
            System.setProperty("http.proxyPort", Integer.toString(proxy.port()));
            System.setProperty("https.proxyHost", "127.0.0.1");
            System.setProperty("https.proxyPort", Integer.toString(proxy.port()));
            System.setProperty("socksProxyHost", "127.0.0.1");
            System.setProperty("socksProxyPort", Integer.toString(proxy.port()));
            System.setProperty("java.net.useSystemProxies", "true");
            ProxySelector.setDefault(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    return List.of(new Proxy(Proxy.Type.HTTP,
                            InetSocketAddress.createUnresolved("127.0.0.1", proxy.port())));
                }

                @Override
                public void connectFailed(URI uri, SocketAddress address, IOException exception) {
                    // The direct Apache route must never call this selector.
                }
            });
            ControllableHostAddressLookup lookup = new ControllableHostAddressLookup().enqueue(loopback(2));
            try (CloseableHttpClient client = client(testPolicy(), lookup)) {
                try (ClassicHttpResponse response = execute(client,
                        "http://" + TARGET_HOST + ":" + permitted.port() + "/direct")) {
                    assertEquals(200, response.getCode());
                    EntityUtils.consume(response.getEntity());
                }
            }
            try (CloseableHttpClient client = WebhookTlsIdentityIntegrationTest.clientFor(secure)) {
                try (ClassicHttpResponse response = execute(client,
                        "https://webhook.test:" + secure.port() + "/direct")) {
                    assertEquals(200, response.getCode());
                    EntityUtils.consume(response.getEntity());
                }
            }
            assertEquals(1, permitted.requestsSeen.get());
            assertEquals(loopback(2), permitted.localPeers.get(0));
            assertEquals(1, secure.requestBytes.get());
            assertEquals(loopback(2), secure.localPeers.get(0));
            assertEquals(0, proxy.acceptedSockets.get());
        } finally {
            ProxySelector.setDefault(oldProxySelector);
            restore(old);
        }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void redirectStatusesNeverOpenTrapConnection(int status) throws Exception {
        try (HttpListener trap = HttpListener.keepAlive(loopback(1), 1);
                HttpListener destination = HttpListener.redirect(loopback(2), 1, status, trap.port())) {
            ControllableHostAddressLookup lookup = new ControllableHostAddressLookup().enqueue(loopback(2));
            try (CloseableHttpClient client = client(testPolicy(), lookup)) {
                try (ClassicHttpResponse response = execute(client,
                        "http://" + TARGET_HOST + ":" + destination.port() + "/redirect")) {
                    assertEquals(status, response.getCode());
                    EntityUtils.consume(response.getEntity());
                }
            }
            assertEquals(1, destination.requestsSeen.get());
            assertEquals(0, trap.acceptedSockets.get());
            assertEquals(1, lookup.invocationCount());
        }
    }

    private static CloseableHttpClient client(PublicDestinationAddressPolicy policy,
            ControllableHostAddressLookup lookup) {
        DeliveryHttpClientConfig config = new DeliveryHttpClientConfig();
        return config.deliveryApacheHttpClient(config.deliveryConnectionManager(
                new PolicyEnforcingDnsResolver(lookup, policy)));
    }

    private static ClassicHttpResponse execute(CloseableHttpClient client, String uri) throws IOException {
        try (DeliveryDeadlineContext.Scope ignored = DeliveryDeadlineContext.open(
                DeliveryDeadline.start(Duration.ofSeconds(5), System::nanoTime))) {
            return client.execute(new HttpGet(uri));
        }
    }

    private static PublicDestinationAddressPolicy testPolicy() throws IOException {
        return policyBlocking(loopback(99));
    }

    private static PublicDestinationAddressPolicy policyBlocking(InetAddress address) {
        String ip = address.getHostAddress();
        return new PublicDestinationAddressPolicy(new SpecialPurposeAddressCatalog(new ByteArrayInputStream(
                (ip + "/32|TEST_TRAP\n").getBytes(StandardCharsets.US_ASCII))));
    }

    private static InetAddress loopback(int lastOctet) throws IOException {
        return InetAddress.getByAddress(new byte[] {127, 0, 0, (byte) lastOctet});
    }

    private static Map<String, String> remember(String[] keys) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (String key : keys) {
            values.put(key, System.getProperty(key));
        }
        return values;
    }

    private static void restore(Map<String, String> old) {
        old.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    private static final class HttpListener implements AutoCloseable {
        private final ServerSocket server;
        private final Mode mode;
        private final int expectedRequests;
        private final int status;
        private final int redirectPort;
        private final AtomicInteger acceptedSockets = new AtomicInteger();
        private final AtomicInteger requestsSeen = new AtomicInteger();
        private final CountDownLatch requestLatch;
        private final CountDownLatch connectionClosed = new CountDownLatch(1);
        private final List<InetAddress> localPeers = new CopyOnWriteArrayList<>();
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        private HttpListener(InetAddress bind, int expectedRequests, int port, Mode mode, int status,
                int redirectPort) throws IOException {
            server = new ServerSocket(port, 8, bind);
            this.mode = mode;
            this.expectedRequests = expectedRequests;
            this.status = status;
            this.redirectPort = redirectPort;
            requestLatch = new CountDownLatch(expectedRequests);
            executor.submit(this::accept);
        }

        static HttpListener keepAlive(InetAddress bind, int expected) throws IOException {
            return new HttpListener(bind, expected, 0, Mode.KEEP_ALIVE, 200, -1);
        }

        static HttpListener keepAlive(InetAddress bind, int expected, int port) throws IOException {
            return new HttpListener(bind, expected, port, Mode.KEEP_ALIVE, 200, -1);
        }

        static HttpListener closeAfterResponse(InetAddress bind, int expected) throws IOException {
            return new HttpListener(bind, expected, 0, Mode.CLOSE, 200, -1);
        }

        static HttpListener redirect(InetAddress bind, int expected, int status, int redirectPort)
                throws IOException {
            return new HttpListener(bind, expected, 0, Mode.REDIRECT, status, redirectPort);
        }

        int port() {
            return server.getLocalPort();
        }

        private void accept() {
            try {
                while (!server.isClosed() && acceptedSockets.get() < expectedRequests) {
                    Socket socket = server.accept();
                    acceptedSockets.incrementAndGet();
                    executor.submit(() -> serve(socket));
                }
            } catch (IOException ignored) {
                // close interrupts accept
            }
        }

        private void serve(Socket socket) {
            try (socket; BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                    OutputStream output = socket.getOutputStream()) {
                localPeers.add(socket.getLocalAddress());
                while (true) {
                    String line = reader.readLine();
                    if (line == null) {
                        return;
                    }
                    Map<String, String> headers = new java.util.HashMap<>();
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        int colon = line.indexOf(':');
                        if (colon > 0) {
                            headers.put(line.substring(0, colon).toLowerCase(), line.substring(colon + 1).trim());
                        }
                    }
                    int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                    for (int i = 0; i < length; i++) {
                        if (reader.read() < 0) {
                            return;
                        }
                    }
                    requestsSeen.incrementAndGet();
                    requestLatch.countDown();
                    String connection = mode == Mode.CLOSE || mode == Mode.REDIRECT
                            || requestsSeen.get() >= expectedRequests ? "close" : "keep-alive";
                    String response = mode == Mode.REDIRECT
                            ? "HTTP/1.1 " + status + " Redirect\r\nLocation: http://trap.test:" + redirectPort
                                    + "/trap\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            : "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: " + connection
                                    + "\r\n\r\nok";
                    output.write(response.getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    if (mode != Mode.KEEP_ALIVE || requestsSeen.get() >= expectedRequests) {
                        connectionClosed.countDown();
                        return;
                    }
                }
            } catch (IOException ignored) {
                // teardown or an intentionally refused exchange
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            executor.close();
        }

        private enum Mode { KEEP_ALIVE, CLOSE, REDIRECT }
    }
}
