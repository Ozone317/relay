package com.example.relay.deliveryengine.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.Test;

import com.example.relay.deliveryengine.config.DeliveryHttpClientConfig;
import com.example.relay.deliveryengine.destination.HostAddressLookup;
import com.example.relay.deliveryengine.destination.PolicyEnforcingDnsResolver;
import com.example.relay.deliveryengine.destination.PublicDestinationAddressPolicy;
import com.example.relay.deliveryengine.destination.SpecialPurposeAddressCatalog;

class ApacheWebhookHttpTransportTest {

    @Test
    void sendsExactBytesAndRelayHeadersWithoutFollowingRedirects() throws Exception {
        byte[] body = "{\"message\":\"héllo 🌍\"}".getBytes(StandardCharsets.UTF_8);
        AtomicReference<byte[]> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedHeaders = new AtomicReference<>();

        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
                CloseableHttpClient client = clientForLoopback();
                var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            Thread.startVirtualThread(() -> capture(server, receivedBody, receivedHeaders));
            ApacheWebhookHttpTransport transport = new ApacheWebhookHttpTransport(client,
                    input -> new String(input.readAllBytes(), StandardCharsets.UTF_8), scheduler);

            WebhookHttpResponse response = transport.post("http://webhook.test:" + server.getLocalPort() + "/hook",
                    body, new WebhookHeaders("relay-1", 1700000000L, "sig"));

            assertEquals(302, response.statusCode());
            assertArrayEquals(body, receivedBody.get());
            String headers = receivedHeaders.get();
            org.junit.jupiter.api.Assertions.assertTrue(headers.contains("relay-id: relay-1"));
            org.junit.jupiter.api.Assertions.assertTrue(headers.contains("relay-timestamp: 1700000000"));
            org.junit.jupiter.api.Assertions.assertTrue(headers.contains("relay-signature: sig"));
            org.junit.jupiter.api.Assertions.assertTrue(headers.contains("content-type: application/json"));
            org.junit.jupiter.api.Assertions.assertTrue(headers.contains("host: webhook.test:" + server.getLocalPort()));
        }
    }

    @Test
    void mapsInvalidPersistedUriToStableFailureCode() throws Exception {
        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            ApacheWebhookHttpTransport transport = new ApacheWebhookHttpTransport(null,
                    input -> "", scheduler);
            WebhookDeliveryException exception = assertThrows(WebhookDeliveryException.class,
                    () -> transport.post("file:///tmp/nope", new byte[0], new WebhookHeaders("id", 1, "sig")));
            assertEquals(WebhookFailureCode.DESTINATION_INVALID, exception.failureCode());
        }
    }

    private static CloseableHttpClient clientForLoopback() {
        DeliveryHttpClientConfig config = new DeliveryHttpClientConfig();
        SpecialPurposeAddressCatalog catalog = new SpecialPurposeAddressCatalog(
                new java.io.ByteArrayInputStream("0.0.0.0/32|TEST_SENTINEL\n".getBytes(StandardCharsets.UTF_8)));
        HostAddressLookup lookup = (hostname, deadline) -> List.of(InetAddress.getLoopbackAddress());
        PolicyEnforcingDnsResolver resolver = new PolicyEnforcingDnsResolver(lookup,
                new PublicDestinationAddressPolicy(catalog));
        PoolingHttpClientConnectionManager manager = config.deliveryConnectionManager(resolver);
        return config.deliveryApacheHttpClient(manager);
    }

    private static void capture(ServerSocket server, AtomicReference<byte[]> body,
            AtomicReference<String> headers) {
        try (Socket socket = server.accept();
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                        StandardCharsets.ISO_8859_1));
                OutputStream output = socket.getOutputStream()) {
            StringBuilder requestHeaders = new StringBuilder();
            String line;
            int contentLength = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                requestHeaders.append(line.toLowerCase()).append('\n');
                if (line.regionMatches(true, 0, "content-length:", 0, 15)) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }
            headers.set(requestHeaders.toString());
            ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
            for (int i = 0; i < contentLength; i++) {
                requestBody.write(reader.read());
            }
            body.set(requestBody.toByteArray());
            output.write("HTTP/1.1 302 Found\r\nLocation: http://trap.test/\r\nContent-Length: 0\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (Exception ignored) {
            // Test fixture teardown may close the listening socket.
        }
    }
}
