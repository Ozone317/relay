package com.example.relay.deliveryengine.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.deliveryengine.config.DeliveryHttpClientConfig;
import com.example.relay.deliveryengine.destination.PolicyEnforcingDnsResolver;
import com.example.relay.deliveryengine.destination.PublicDestinationAddressPolicy;
import com.example.relay.deliveryengine.destination.SpecialPurposeAddressCatalog;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.TrustManagerFactory;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WebhookTlsIdentityIntegrationTest {

    private static final String HOST = "webhook.test";
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void validCertificatePreservesSniAndHttpAuthority() throws Exception {
        try (TlsListener server = TlsListener.start("p03-webhook-test-cert.pem", "p03-webhook-test-key.pem");
                CloseableHttpClient client = client(server, "p03-webhook-test-cert.pem")) {
            try (var response = execute(client, server.port())) {
                assertEquals(200, response.getCode());
                EntityUtils.consume(response.getEntity());
            }
            assertTrue(server.requestSeen.await(5, TimeUnit.SECONDS));
            assertTrue(server.terminal.await(5, TimeUnit.SECONDS));
            assertEquals(1, server.acceptedSockets.get());
            assertEquals(1, server.requestBytes.get());
            assertEquals(1, server.lookup.invocationCount());
            assertEquals(List.of("webhook.test."), server.lookup.absoluteHostnames());
            assertEquals(List.of(loopback(2)), server.localPeers);
            assertEquals(HOST, server.sniHost);
            assertEquals(HOST + ":" + server.port(), server.hostHeader);
        }
    }

    @Test
    void trustedWrongHostCertificateFailsHostnameVerificationBeforeHttpBytes() throws Exception {
        try (TlsListener server = TlsListener.start("p03-wrong-host-cert.pem", "p03-wrong-host-key.pem");
                CloseableHttpClient client = client(server, "p03-webhook-test-cert.pem")) {
            assertThrows(IOException.class, () -> execute(client, server.port()));
            assertTrue(server.accepted.await(5, TimeUnit.SECONDS));
            assertTrue(server.terminal.await(5, TimeUnit.SECONDS));
            assertEquals(0, server.requestBytes.get(), "hostname failure must precede HTTP request bytes");
            assertEquals(1, server.lookup.invocationCount());
            assertEquals(List.of("webhook.test."), server.lookup.absoluteHostnames());
            assertEquals(List.of(loopback(2)), server.localPeers);
        }
    }

    private CloseableHttpClient client(TlsListener server, String ignoredServerCertificate) throws Exception {
        return clientFor(server);
    }

    static CloseableHttpClient clientFor(TlsListener server) throws Exception {
        SSLContext clientContext = trustContext(certificate("p03-test-ca.pem"));
        ControllableHostAddressLookup lookup = new ControllableHostAddressLookup().enqueue(loopback(2));
        server.lookup = lookup;
        PolicyEnforcingDnsResolver resolver = new PolicyEnforcingDnsResolver(lookup, testPolicy());
        PoolingHttpClientConnectionManager manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setTlsSocketStrategy(ClientTlsStrategyBuilder.create().setSslContext(clientContext).buildClassic())
                .setMaxConnTotal(4)
                .setMaxConnPerRoute(4)
                .build();
        return new DeliveryHttpClientConfig().deliveryApacheHttpClient(manager);
    }

    private static org.apache.hc.client5.http.impl.classic.CloseableHttpResponse execute(CloseableHttpClient client,
            int port) throws IOException {
        try (DeliveryDeadlineContext.Scope ignored = DeliveryDeadlineContext.open(
                DeliveryDeadline.start(java.time.Duration.ofSeconds(5), System::nanoTime))) {
            return client.execute(new org.apache.hc.client5.http.classic.methods.HttpGet(
                    "https://" + HOST + ":" + port + "/tls"));
        }
    }

    private static PublicDestinationAddressPolicy testPolicy() throws IOException {
        return new PublicDestinationAddressPolicy(new SpecialPurposeAddressCatalog(new ByteArrayInputStream(
                "127.0.0.99/32|TEST_SENTINEL\n".getBytes(StandardCharsets.US_ASCII))));
    }

    private static InetAddress loopback(int lastOctet) throws IOException {
        return InetAddress.getByAddress(new byte[] {127, 0, 0, (byte) lastOctet});
    }

    private static Certificate certificate(String name) throws Exception {
        try (InputStream input = resource(name)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static PrivateKey privateKey(String name) throws Exception {
        byte[] der = pemDer(name, "PRIVATE KEY");
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static SSLContext trustContext(Certificate ca) throws Exception {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("p03-ca", ca);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        return context;
    }

    private static SSLContext serverContext(String certificateName, String keyName) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        Certificate certificate = certificate(certificateName);
        keyStore.setKeyEntry("p03-server", privateKey(keyName), "changeit".toCharArray(),
                new Certificate[] {certificate, certificate("p03-test-ca.pem")});
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, "changeit".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }

    private static byte[] pemDer(String name, String label) throws IOException {
        String pem = new String(resource(name).readAllBytes(), StandardCharsets.US_ASCII);
        String base64 = pem.replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s", "");
        return java.util.Base64.getDecoder().decode(base64);
    }

    private static InputStream resource(String name) {
        InputStream input = WebhookTlsIdentityIntegrationTest.class.getResourceAsStream("/tls/" + name);
        if (input == null) {
            throw new IllegalArgumentException("missing test TLS resource " + name);
        }
        return input;
    }

    static final class TlsListener implements AutoCloseable {
        private final SSLServerSocket server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final CountDownLatch accepted = new CountDownLatch(1);
        private final CountDownLatch requestSeen = new CountDownLatch(1);
        private final CountDownLatch terminal = new CountDownLatch(1);
        private final AtomicInteger acceptedSockets = new AtomicInteger();
        final AtomicInteger requestBytes = new AtomicInteger();
        final List<InetAddress> localPeers = new CopyOnWriteArrayList<>();
        volatile ControllableHostAddressLookup lookup;
        private volatile String sniHost;
        private volatile String hostHeader;

        static TlsListener start(String certificate, String key) throws Exception {
            SSLServerSocket server = (SSLServerSocket) serverContext(certificate, key)
                    .getServerSocketFactory().createServerSocket(0, 8, loopback(2));
            TlsListener listener = new TlsListener(server);
            listener.executor.submit(listener::serve);
            return listener;
        }

        private TlsListener(SSLServerSocket server) {
            this.server = server;
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            try (SSLSocket socket = (SSLSocket) server.accept()) {
                acceptedSockets.incrementAndGet();
                localPeers.add(socket.getLocalAddress());
                accepted.countDown();
                try {
                    socket.startHandshake();
                    ExtendedSSLSession session = (ExtendedSSLSession) socket.getSession();
                    if (!session.getRequestedServerNames().isEmpty()) {
                        if (session.getRequestedServerNames().get(0) instanceof SNIHostName hostName) {
                            sniHost = hostName.getAsciiName();
                        }
                    }
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                            StandardCharsets.ISO_8859_1));
                    String line = reader.readLine();
                    if (line == null) {
                        return;
                    }
                    requestBytes.incrementAndGet();
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.regionMatches(true, 0, "Host:", 0, 5)) {
                            hostHeader = line.substring(5).trim();
                        }
                    }
                    requestSeen.countDown();
                    OutputStream output = socket.getOutputStream();
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                            .getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                } catch (IOException ignored) {
                    // A wrong-host certificate is expected to fail during handshake.
                }
            } catch (IOException ignored) {
                // close interrupts accept
            } finally {
                terminal.countDown();
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            executor.close();
        }
    }
}
