package com.example.relay.deliveryengine.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.relay.deliveryengine.destination.HostAddressLookup;
import com.example.relay.deliveryengine.destination.PolicyEnforcingDnsResolver;
import com.example.relay.deliveryengine.destination.PublicDestinationAddressPolicy;
import com.example.relay.deliveryengine.destination.SpecialPurposeAddressCatalog;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.io.DetachedSocketFactory;
import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.config.Lookup;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import org.junit.jupiter.api.Test;

class ApacheWebhookHttpTransportDeadlineTest {

    @Test
    void clearsDeadlineContextAfterUnexpectedExchangeException() throws Exception {
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            ApacheWebhookHttpTransport transport = new ApacheWebhookHttpTransport(new ThrowingClient(), input -> "",
                    scheduler, new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofMillis(100));

            assertThrows(IllegalStateException.class,
                    () -> transport.post("http://webhook.test/hook", new byte[0], new WebhookHeaders("id", 1, "sig")));
            assertThrows(IllegalStateException.class, DeliveryDeadlineContext::current);
        }
    }

    @Test
    void mapsResponseConsumerStallToDeadlineTimeoutAndClosesRealResponseIo() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        AtomicReference<Socket> acceptedSocket = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
                CloseableHttpClient client = clientForLoopback();
                ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
                ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
            Thread.startVirtualThread(() -> withholdResponseBody(server, headersSent, acceptedSocket));
            ApacheWebhookHttpTransport transport = new ApacheWebhookHttpTransport(client, input -> {
                input.read();
                return "unreachable";
            }, scheduler, new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofMillis(120));
            Future<WebhookHttpResponse> result =
                    calls.submit(() -> transport.post("http://webhook.test:" + server.getLocalPort() + "/hook",
                            new byte[0], new WebhookHeaders("id", 1, "sig")));
            assertEquals(true, headersSent.await(1, TimeUnit.SECONDS));
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
            assertEquals(WebhookFailureCode.DELIVERY_TIMEOUT,
                    ((WebhookDeliveryException) failure.getCause()).failureCode());
            assertThrows(IllegalStateException.class, DeliveryDeadlineContext::current);
        } finally {
            closeQuietly(acceptedSocket.get());
        }
    }

    @Test
    void mapsExpectedResolverFailuresWithoutLeavingDeadlineScope() throws Exception {
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            ApacheWebhookHttpTransport dnsTransport =
                    new ApacheWebhookHttpTransport(new DnsFailureClient(), input -> "", scheduler,
                            new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofMillis(100));
            WebhookDeliveryException dns = assertThrows(WebhookDeliveryException.class, () -> dnsTransport
                    .post("http://webhook.test/hook", new byte[0], new WebhookHeaders("id", 1, "sig")));
            assertEquals(WebhookFailureCode.DNS_RESOLUTION_FAILED, dns.failureCode());
            assertThrows(IllegalStateException.class, DeliveryDeadlineContext::current);

            ApacheWebhookHttpTransport policyTransport =
                    new ApacheWebhookHttpTransport(new PolicyFailureClient(), input -> "", scheduler,
                            new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofMillis(100));
            WebhookDeliveryException policy = assertThrows(WebhookDeliveryException.class, () -> policyTransport
                    .post("http://webhook.test/hook", new byte[0], new WebhookHeaders("id", 1, "sig")));
            assertEquals(WebhookFailureCode.DESTINATION_POLICY_BLOCKED, policy.failureCode());
            assertThrows(IllegalStateException.class, DeliveryDeadlineContext::current);
        }
    }

    @Test
    void mapsLateUnknownHostAfterAbsoluteDeadlineToDeliveryTimeout() throws Exception {
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            ApacheWebhookHttpTransport transport =
                    new ApacheWebhookHttpTransport(new LateDnsFailureClient(), input -> "", scheduler,
                            new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofMillis(20));
            WebhookDeliveryException exception = assertThrows(WebhookDeliveryException.class,
                    () -> transport.post("http://webhook.test/hook", new byte[0], new WebhookHeaders("id", 1, "sig")));
            assertEquals(WebhookFailureCode.DELIVERY_TIMEOUT, exception.failureCode());
        }
    }

    @Test
    void doesNotExecuteOrConfigureAZeroRemainingTimeout() throws Exception {
        AtomicBoolean executed = new AtomicBoolean();
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            ApacheWebhookHttpTransport transport =
                    new ApacheWebhookHttpTransport(new RecordingExecutionClient(executed), input -> "", scheduler,
                            new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ZERO);
            WebhookDeliveryException exception = assertThrows(WebhookDeliveryException.class,
                    () -> transport.post("http://webhook.test/hook", new byte[0], new WebhookHeaders("id", 1, "sig")));
            assertEquals(WebhookFailureCode.DELIVERY_TIMEOUT, exception.failureCode());
            assertFalse(executed.get());
        }
    }

    @Test
    void deadlineContextExistsOnlyDuringApacheExecuteNotConsumerOrResponseClose() throws Exception {
        AtomicBoolean executeSawContext = new AtomicBoolean();
        AtomicBoolean consumerSawContext = new AtomicBoolean();
        AtomicBoolean closeSawContext = new AtomicBoolean();
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            ApacheWebhookHttpTransport transport = new ApacheWebhookHttpTransport(
                    new ContextRecordingClient(executeSawContext, closeSawContext), input -> {
                        consumerSawContext.set(hasDeadlineContext());
                        return "";
                    }, scheduler, new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofMillis(100));
            transport.post("http://webhook.test/hook", new byte[0], new WebhookHeaders("id", 1, "sig"));
        }
        assertEquals(true, executeSawContext.get());
        assertFalse(consumerSawContext.get());
        assertFalse(closeSawContext.get());
    }

    @Test
    void poolAcquisitionUsesOnlyRemainingSharedBudget() throws Exception {
        CountDownLatch firstResponseBodyHeld = new CountDownLatch(1);
        AtomicReference<Socket> firstSocket = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
                CloseableHttpClient client = clientForLoopback(1);
                ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
                ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
            Thread.startVirtualThread(() -> withholdResponseBody(server, firstResponseBodyHeld, firstSocket));
            ApacheWebhookHttpTransport first = new ApacheWebhookHttpTransport(client, input -> {
                firstResponseBodyHeld.countDown();
                input.read();
                return "unreachable";
            }, scheduler, new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofMillis(160));
            Future<WebhookHttpResponse> firstResult =
                    calls.submit(() -> first.post("http://webhook.test:" + server.getLocalPort() + "/first",
                            new byte[0], new WebhookHeaders("id", 1, "sig")));
            assertEquals(true, firstResponseBodyHeld.await(1, TimeUnit.SECONDS));

            ApacheWebhookHttpTransport second = new ApacheWebhookHttpTransport(client, input -> "", scheduler,
                    new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofMillis(160));
            long started = System.nanoTime();
            WebhookDeliveryException exception = assertThrows(WebhookDeliveryException.class,
                    () -> second.post("http://webhook.test:" + server.getLocalPort() + "/second", new byte[0],
                            new WebhookHeaders("id", 1, "sig")));
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertEquals(WebhookFailureCode.DELIVERY_TIMEOUT, exception.failureCode());
            org.junit.jupiter.api.Assertions.assertTrue(elapsedMillis >= 100,
                    "pool contention should consume the configured budget: " + elapsedMillis);
            org.junit.jupiter.api.Assertions.assertTrue(elapsedMillis < 360,
                    "pool contention must not receive independent phase budgets: " + elapsedMillis);
            firstResult.cancel(true);
        } finally {
            closeQuietly(firstSocket.get());
        }
    }

    @Test
    void tcpConnectUsesRemainingBudgetThroughApacheConnectionOperator() throws Exception {
        CountDownLatch connectStarted = new CountDownLatch(1);
        AtomicReference<Integer> connectTimeoutMillis = new AtomicReference<>();
        BlockingSocketFactory socketFactory = new BlockingSocketFactory(connectStarted, connectTimeoutMillis);
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
                CloseableHttpClient client = clientForSocketFactory(socketFactory)) {
            ApacheWebhookHttpTransport transport = new ApacheWebhookHttpTransport(client, input -> "", scheduler,
                    new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofMillis(160));
            long started = System.nanoTime();
            WebhookDeliveryException exception = assertThrows(WebhookDeliveryException.class,
                    () -> transport.post("http://webhook.test/hook", new byte[0], new WebhookHeaders("id", 1, "sig")));
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertEquals(WebhookFailureCode.DELIVERY_TIMEOUT, exception.failureCode());
            assertEquals(true, connectStarted.await(1, TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertTrue(connectTimeoutMillis.get() > 0);
            org.junit.jupiter.api.Assertions.assertTrue(connectTimeoutMillis.get() <= 160);
            org.junit.jupiter.api.Assertions.assertTrue(elapsedMillis < 360,
                    "TCP connect must not receive independent phase budgets: " + elapsedMillis);
        }
    }

    @Test
    void responseHeaderStallUsesOnlyRemainingSharedBudget() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        AtomicReference<Socket> acceptedSocket = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
                CloseableHttpClient client = clientForLoopback();
                ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
                ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
            Thread.startVirtualThread(() -> withholdResponseHeaders(server, requestReceived, acceptedSocket));
            ApacheWebhookHttpTransport transport = new ApacheWebhookHttpTransport(client, input -> "", scheduler,
                    new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofMillis(160));
            Future<WebhookHttpResponse> result =
                    calls.submit(() -> transport.post("http://webhook.test:" + server.getLocalPort() + "/headers",
                            new byte[0], new WebhookHeaders("id", 1, "sig")));
            assertEquals(true, requestReceived.await(1, TimeUnit.SECONDS));
            long started = System.nanoTime();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertEquals(WebhookFailureCode.DELIVERY_TIMEOUT,
                    ((WebhookDeliveryException) failure.getCause()).failureCode());
            org.junit.jupiter.api.Assertions.assertTrue(elapsedMillis < 360,
                    "response-header stall must not receive independent phase budgets: " + elapsedMillis);
        } finally {
            closeQuietly(acceptedSocket.get());
        }
    }

    @Test
    void clearsContextForEveryModeOnOneExecutorThread() throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
                ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            executor.submit(() -> {
                ApacheWebhookHttpTransport success = new ApacheWebhookHttpTransport(new ResponseClient(), input -> "",
                        scheduler, new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofMillis(100));
                success.post("http://webhook.test/hook", new byte[0], new WebhookHeaders("id", 1, "sig"));
                assertThrows(IllegalStateException.class, DeliveryDeadlineContext::current);

                ApacheWebhookHttpTransport dns = new ApacheWebhookHttpTransport(new DnsFailureClient(), input -> "",
                        scheduler, new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofMillis(100));
                assertThrows(WebhookDeliveryException.class,
                        () -> dns.post("http://webhook.test/hook", new byte[0], new WebhookHeaders("id", 1, "sig")));
                assertThrows(IllegalStateException.class, DeliveryDeadlineContext::current);

                ApacheWebhookHttpTransport policy =
                        new ApacheWebhookHttpTransport(new PolicyFailureClient(), input -> "", scheduler,
                                new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofMillis(100));
                assertThrows(WebhookDeliveryException.class,
                        () -> policy.post("http://webhook.test/hook", new byte[0], new WebhookHeaders("id", 1, "sig")));
                assertThrows(IllegalStateException.class, DeliveryDeadlineContext::current);

                ApacheWebhookHttpTransport io = new ApacheWebhookHttpTransport(new IoFailureClient(), input -> "",
                        scheduler, new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofMillis(100));
                assertThrows(WebhookDeliveryException.class,
                        () -> io.post("http://webhook.test/hook", new byte[0], new WebhookHeaders("id", 1, "sig")));
                assertThrows(IllegalStateException.class, DeliveryDeadlineContext::current);

                ApacheWebhookHttpTransport timeout =
                        new ApacheWebhookHttpTransport(new LateDnsFailureClient(), input -> "", scheduler,
                                new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofMillis(10));
                assertThrows(WebhookDeliveryException.class, () -> timeout.post("http://webhook.test/hook", new byte[0],
                        new WebhookHeaders("id", 1, "sig")));
                assertThrows(IllegalStateException.class, DeliveryDeadlineContext::current);

                ApacheWebhookHttpTransport unexpected =
                        new ApacheWebhookHttpTransport(new ThrowingClient(), input -> "", scheduler,
                                new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofMillis(100));
                assertThrows(IllegalStateException.class, () -> unexpected.post("http://webhook.test/hook", new byte[0],
                        new WebhookHeaders("id", 1, "sig")));
                assertThrows(IllegalStateException.class, DeliveryDeadlineContext::current);

                ApacheWebhookHttpTransport finalSuccess =
                        new ApacheWebhookHttpTransport(new ResponseClient(), input -> "", scheduler,
                                new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofMillis(100));
                finalSuccess.post("http://webhook.test/final", new byte[0], new WebhookHeaders("id", 1, "sig"));
                assertThrows(IllegalStateException.class, DeliveryDeadlineContext::current);
                return null;
            }).get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void actualApacheManagerRecordsValidatedSocketTlsNameAndHttpAuthorityTogether() throws Exception {
        AtomicReference<String> selectedSocketIp = new AtomicReference<>();
        AtomicReference<String> tlsTargetName = new AtomicReference<>();
        AtomicReference<String> httpAuthority = new AtomicReference<>();
        CountDownLatch accepted = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
                ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            Thread.startVirtualThread(() -> acceptAndClose(server, accepted));
            HostAddressLookup lookup = (hostname, deadline) -> List.of(InetAddress.getLoopbackAddress());
            SpecialPurposeAddressCatalog catalog = new SpecialPurposeAddressCatalog(
                    new java.io.ByteArrayInputStream("0.0.0.0/32|TEST_SENTINEL\n".getBytes(StandardCharsets.UTF_8)));
            PolicyEnforcingDnsResolver resolver =
                    new PolicyEnforcingDnsResolver(lookup, new PublicDestinationAddressPolicy(catalog));
            TlsSocketStrategy tls = (socket, host, port, attachment, context) -> {
                selectedSocketIp.set(socket.getInetAddress().getHostAddress());
                tlsTargetName.set(host);
                throw new IOException("test TLS seam stop");
            };
            PoolingHttpClientConnectionManager manager = PoolingHttpClientConnectionManagerBuilder.create()
                    .setDnsResolver(resolver).setTlsSocketStrategy(tls)
                    .setDefaultSocketConfig(SocketConfig.custom().setSocksProxyAddress(null).build()).setMaxConnTotal(1)
                    .setMaxConnPerRoute(1).setConnectionTimeToLive(TimeValue.ofMinutes(1)).build();
            try (CloseableHttpClient actual = HttpClients.custom().setConnectionManager(manager)
                    .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                    .disableRedirectHandling().disableAutomaticRetries().build(); var ignored = manager) {
                CloseableHttpClient recording = new AuthorityRecordingClient(actual, httpAuthority);
                ApacheWebhookHttpTransport transport = new ApacheWebhookHttpTransport(recording, input -> "", scheduler,
                        new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofSeconds(1));
                WebhookDeliveryException exception = assertThrows(WebhookDeliveryException.class,
                        () -> transport.post("https://Hooks.Example.Test:" + server.getLocalPort() + "/hook",
                                new byte[0], new WebhookHeaders("id", 1, "sig")));
                assertEquals(WebhookFailureCode.TRANSPORT_FAILURE, exception.failureCode());
            }
            assertEquals(true, accepted.await(1, TimeUnit.SECONDS));
        }
        assertEquals("127.0.0.1", selectedSocketIp.get());
        assertEquals("hooks.example.test", tlsTargetName.get());
        assertEquals("hooks.example.test", httpAuthority.get());
    }

    @Test
    void keepsNormalizedHostnameAsHttpAndTlsRouteIdentity() throws Exception {
        AtomicReference<org.apache.hc.core5.http.HttpHost> target = new AtomicReference<>();
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            ApacheWebhookHttpTransport transport =
                    new ApacheWebhookHttpTransport(new RecordingClient(target), input -> "", scheduler,
                            new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofMillis(100));
            transport.post("HTTP://Hooks.Example.Test:8080/path", new byte[0], new WebhookHeaders("id", 1, "sig"));
        }
        assertEquals("hooks.example.test", target.get().getHostName());
        org.junit.jupiter.api.Assertions.assertNull(target.get().getAddress());
    }

    private static final class ThrowingClient extends CloseableHttpClient {
        @Override
        protected CloseableHttpResponse doExecute(org.apache.hc.core5.http.HttpHost target, ClassicHttpRequest request,
                org.apache.hc.core5.http.protocol.HttpContext context) throws IOException {
            throw new IllegalStateException("invariant failure");
        }

        @Override
        public void close(CloseMode closeMode) {}

        @Override
        public void close() {}
    }

    private static final class ResponseClient extends CloseableHttpClient {
        @Override
        protected CloseableHttpResponse doExecute(org.apache.hc.core5.http.HttpHost target, ClassicHttpRequest request,
                org.apache.hc.core5.http.protocol.HttpContext context) {
            ClassicHttpResponse response = new BasicClassicHttpResponse(200);
            return CloseableHttpResponse.adapt(response);
        }

        @Override
        public void close(CloseMode closeMode) {}

        @Override
        public void close() {}
    }

    private static final class DnsFailureClient extends CloseableHttpClient {
        @Override
        protected CloseableHttpResponse doExecute(org.apache.hc.core5.http.HttpHost target, ClassicHttpRequest request,
                org.apache.hc.core5.http.protocol.HttpContext context) throws IOException {
            throw new UnknownHostException("host unavailable");
        }

        @Override
        public void close(CloseMode closeMode) {}

        @Override
        public void close() {}
    }

    private static final class LateDnsFailureClient extends CloseableHttpClient {
        @Override
        protected CloseableHttpResponse doExecute(org.apache.hc.core5.http.HttpHost target, ClassicHttpRequest request,
                org.apache.hc.core5.http.protocol.HttpContext context) throws IOException {
            try {
                Thread.sleep(60);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            throw new UnknownHostException("host unavailable");
        }

        @Override
        public void close(CloseMode closeMode) {}

        @Override
        public void close() {}
    }

    private static final class RecordingExecutionClient extends CloseableHttpClient {
        private final AtomicBoolean executed;

        private RecordingExecutionClient(AtomicBoolean executed) {
            this.executed = executed;
        }

        @Override
        protected CloseableHttpResponse doExecute(org.apache.hc.core5.http.HttpHost target, ClassicHttpRequest request,
                org.apache.hc.core5.http.protocol.HttpContext context) {
            executed.set(true);
            return CloseableHttpResponse.adapt(new BasicClassicHttpResponse(204));
        }

        @Override
        public void close(CloseMode closeMode) {}

        @Override
        public void close() {}
    }

    private static final class ContextRecordingClient extends CloseableHttpClient {
        private final AtomicBoolean executeSawContext;
        private final AtomicBoolean closeSawContext;

        private ContextRecordingClient(AtomicBoolean executeSawContext, AtomicBoolean closeSawContext) {
            this.executeSawContext = executeSawContext;
            this.closeSawContext = closeSawContext;
        }

        @Override
        protected CloseableHttpResponse doExecute(org.apache.hc.core5.http.HttpHost target, ClassicHttpRequest request,
                org.apache.hc.core5.http.protocol.HttpContext context) {
            executeSawContext.set(hasDeadlineContext());
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(204);
            response.setEntity(new CloseObservingEntity(closeSawContext));
            return CloseableHttpResponse.adapt(response);
        }

        @Override
        public void close(CloseMode closeMode) {}

        @Override
        public void close() {}
    }

    private static boolean hasDeadlineContext() {
        try {
            DeliveryDeadlineContext.current();
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private static CloseableHttpClient clientForLoopback() {
        return clientForLoopback(40);
    }

    private static CloseableHttpClient clientForLoopback(int maxConnections) {
        PolicyEnforcingDnsResolver resolver = loopbackResolver();
        PoolingHttpClientConnectionManager manager =
                PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver)
                        .setDefaultSocketConfig(SocketConfig.custom().setSocksProxyAddress(null).build())
                        .setMaxConnTotal(maxConnections).setMaxConnPerRoute(maxConnections)
                        .setConnectionTimeToLive(TimeValue.ofMinutes(1)).build();
        return HttpClients.custom().setConnectionManager(manager)
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE)).disableRedirectHandling()
                .disableAutomaticRetries().build();
    }

    private static CloseableHttpClient clientForSocketFactory(DetachedSocketFactory socketFactory) {
        PoolingHttpClientConnectionManager manager = new SocketFactoryManagerBuilder(socketFactory)
                .setDnsResolver(loopbackResolver())
                .setDefaultSocketConfig(SocketConfig.custom().setSocksProxyAddress(null).build()).setMaxConnTotal(1)
                .setMaxConnPerRoute(1).setConnectionTimeToLive(TimeValue.ofMinutes(1)).build();
        return HttpClients.custom().setConnectionManager(manager)
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE)).disableRedirectHandling()
                .disableAutomaticRetries().build();
    }

    private static PolicyEnforcingDnsResolver loopbackResolver() {
        HostAddressLookup lookup = (hostname, deadline) -> List.of(InetAddress.getLoopbackAddress());
        return resolverFor(lookup);
    }

    private static PolicyEnforcingDnsResolver resolverFor(HostAddressLookup lookup) {
        SpecialPurposeAddressCatalog catalog = new SpecialPurposeAddressCatalog(
                new java.io.ByteArrayInputStream("0.0.0.0/32|TEST_SENTINEL\n".getBytes(StandardCharsets.UTF_8)));
        return new PolicyEnforcingDnsResolver(lookup, new PublicDestinationAddressPolicy(catalog));
    }

    private static void withholdResponseBody(ServerSocket server, CountDownLatch responseSent,
            AtomicReference<Socket> acceptedSocket) {
        try {
            Socket socket = server.accept();
            acceptedSocket.set(socket);
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                    OutputStream output = socket.getOutputStream()) {
                readRequestHeaders(reader);
                output.write("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                output.flush();
                responseSent.countDown();
                socket.getInputStream().read();
            }
        } catch (IOException ignored) {
            // Closing the accepted socket is the deterministic cancellation signal for this fixture.
        }
    }

    private static void withholdResponseHeaders(ServerSocket server, CountDownLatch requestReceived,
            AtomicReference<Socket> acceptedSocket) {
        try {
            Socket socket = server.accept();
            acceptedSocket.set(socket);
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
                readRequestHeaders(reader);
                requestReceived.countDown();
                socket.getInputStream().read();
            }
        } catch (IOException ignored) {
            // Closing the accepted socket is the deterministic cancellation signal for this fixture.
        }
    }

    private static void readRequestHeaders(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            // Consume the complete request before deliberately stalling the response.
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Fixture teardown.
            }
        }
    }

    private static void acceptAndClose(ServerSocket server, CountDownLatch accepted) {
        try (Socket ignored = server.accept()) {
            accepted.countDown();
        } catch (IOException ignored) {
        }
    }

    private static final class CloseObservingEntity implements org.apache.hc.core5.http.HttpEntity {
        private final AtomicBoolean closeSawContext;

        private CloseObservingEntity(AtomicBoolean closeSawContext) {
            this.closeSawContext = closeSawContext;
        }

        @Override
        public long getContentLength() {
            return 0;
        }

        @Override
        public String getContentType() {
            return null;
        }

        @Override
        public String getContentEncoding() {
            return null;
        }

        @Override
        public boolean isChunked() {
            return false;
        }

        @Override
        public boolean isRepeatable() {
            return true;
        }

        @Override
        public boolean isStreaming() {
            return false;
        }

        @Override
        public java.io.InputStream getContent() {
            return java.io.InputStream.nullInputStream();
        }

        @Override
        public void writeTo(java.io.OutputStream outStream) throws IOException {}

        @Override
        public org.apache.hc.core5.function.Supplier<java.util.List<? extends org.apache.hc.core5.http.Header>> getTrailers() {
            return java.util.List::of;
        }

        @Override
        public void close() {
            closeSawContext.set(hasDeadlineContext());
        }
    }

    private static final class PolicyFailureClient extends CloseableHttpClient {
        @Override
        protected CloseableHttpResponse doExecute(org.apache.hc.core5.http.HttpHost target, ClassicHttpRequest request,
                org.apache.hc.core5.http.protocol.HttpContext context) {
            throw new com.example.relay.deliveryengine.destination.DestinationPolicyBlockedException("LOOPBACK");
        }

        @Override
        public void close(CloseMode closeMode) {}

        @Override
        public void close() {}
    }

    private static final class IoFailureClient extends CloseableHttpClient {
        @Override
        protected CloseableHttpResponse doExecute(org.apache.hc.core5.http.HttpHost target, ClassicHttpRequest request,
                org.apache.hc.core5.http.protocol.HttpContext context) throws IOException {
            throw new IOException("connection failed");
        }

        @Override
        public void close(CloseMode closeMode) {}

        @Override
        public void close() {}
    }

    private static final class SocketFactoryManagerBuilder extends PoolingHttpClientConnectionManagerBuilder {
        private final DetachedSocketFactory socketFactory;

        private SocketFactoryManagerBuilder(DetachedSocketFactory socketFactory) {
            this.socketFactory = socketFactory;
        }

        @Override
        protected HttpClientConnectionOperator createConnectionOperator(SchemePortResolver schemePortResolver,
                DnsResolver dnsResolver, TlsSocketStrategy tlsSocketStrategy) {
            Lookup<TlsSocketStrategy> tlsLookup = name -> tlsSocketStrategy;
            return new DefaultHttpClientConnectionOperator(socketFactory, schemePortResolver, dnsResolver, tlsLookup);
        }
    }

    private static final class BlockingSocketFactory implements DetachedSocketFactory {
        private final CountDownLatch connectStarted;
        private final AtomicReference<Integer> connectTimeoutMillis;

        private BlockingSocketFactory(CountDownLatch connectStarted, AtomicReference<Integer> connectTimeoutMillis) {
            this.connectStarted = connectStarted;
            this.connectTimeoutMillis = connectTimeoutMillis;
        }

        @Override
        public Socket create(Proxy proxy) {
            return new BlockingSocket(connectStarted, connectTimeoutMillis);
        }
    }

    private static final class BlockingSocket extends Socket {
        private final CountDownLatch connectStarted;
        private final AtomicReference<Integer> connectTimeoutMillis;
        private final CountDownLatch closed = new CountDownLatch(1);

        private BlockingSocket(CountDownLatch connectStarted, AtomicReference<Integer> connectTimeoutMillis) {
            this.connectStarted = connectStarted;
            this.connectTimeoutMillis = connectTimeoutMillis;
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            connectTimeoutMillis.set(timeout);
            connectStarted.countDown();
            try {
                if (closed.await(timeout, TimeUnit.MILLISECONDS)) {
                    throw new SocketException("socket closed by request cancellation");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new SocketException("socket connect interrupted");
            }
            throw new SocketTimeoutException("deterministic connect stall");
        }

        @Override
        public void close() throws IOException {
            closed.countDown();
            super.close();
        }
    }

    private static final class RecordingClient extends CloseableHttpClient {
        private final AtomicReference<org.apache.hc.core5.http.HttpHost> target;

        private RecordingClient(AtomicReference<org.apache.hc.core5.http.HttpHost> target) {
            this.target = target;
        }

        @Override
        protected CloseableHttpResponse doExecute(org.apache.hc.core5.http.HttpHost target, ClassicHttpRequest request,
                org.apache.hc.core5.http.protocol.HttpContext context) {
            this.target.set(target);
            return CloseableHttpResponse.adapt(new BasicClassicHttpResponse(204));
        }

        @Override
        public void close(CloseMode closeMode) {}

        @Override
        public void close() {}
    }

    private static final class AuthorityRecordingClient extends CloseableHttpClient {
        private final CloseableHttpClient delegate;
        private final AtomicReference<String> authority;

        private AuthorityRecordingClient(CloseableHttpClient delegate, AtomicReference<String> authority) {
            this.delegate = delegate;
            this.authority = authority;
        }

        @Override
        protected CloseableHttpResponse doExecute(org.apache.hc.core5.http.HttpHost target, ClassicHttpRequest request,
                org.apache.hc.core5.http.protocol.HttpContext context) throws IOException {
            authority.set(request.getAuthority().getHostName());
            return delegate.execute(target, request, context);
        }

        @Override
        public void close(CloseMode closeMode) {
            delegate.close(closeMode);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
