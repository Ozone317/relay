package com.example.relay.deliveryengine.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.EofSensorInputStream;
import org.junit.jupiter.api.Test;

class ApacheResponseConsumptionIntegrationTest {

    @Test
    void streamingEntityUsesEofSensorAndEofReleasesConnectionForReuse() throws Exception {
        AtomicReference<Class<?>> streamType = new AtomicReference<>();
        AtomicInteger acceptedConnections = new AtomicInteger();
        CountDownLatch served = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
                CloseableHttpClient client = HttpClients.custom().disableRedirectHandling().disableAutomaticRetries()
                        .build();
                ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            Thread.startVirtualThread(() -> serveTwoResponses(server, acceptedConnections, served));
            ApacheWebhookHttpTransport transport = new ApacheWebhookHttpTransport(client, input -> {
                streamType.set(input.getClass());
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }, scheduler, new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofSeconds(2));

            WebhookHttpResponse first = transport.post("http://127.0.0.1:" + server.getLocalPort() + "/one",
                    new byte[0], new WebhookHeaders("id", 1, "sig"));
            WebhookHttpResponse second = transport.post("http://127.0.0.1:" + server.getLocalPort() + "/two",
                    new byte[0], new WebhookHeaders("id", 1, "sig"));

            assertEquals("one", first.responseBody());
            assertEquals("two", second.responseBody());
            assertEquals(true, served.await(1, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertSame(EofSensorInputStream.class, streamType.get());
        assertEquals(1, acceptedConnections.get());
    }

    @Test
    void oversizedFixedLengthResponseAbortsAndNextRequestUsesNewConnection() throws Exception {
        ConsumptionFixture fixture = new ConsumptionFixture(false);
        try (fixture; CloseableHttpClient client = fixture.client();
                ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            ApacheWebhookHttpTransport transport = fixture.transport(client, scheduler, Duration.ofSeconds(2));
            WebhookHttpResponse first = transport.post(fixture.url("/fixed"), new byte[0], headers());
            assertTrue(first.responseBody().endsWith("[relay response truncated at 10240 bytes]"));
            WebhookHttpResponse second = transport.post(fixture.url("/fixed-next"), new byte[0], headers());
            assertEquals("ok", second.responseBody());
            assertTrue(fixture.limitReached.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(fixture.clientClosed.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(10_241, fixture.firstBytesWritten.get());
            assertEquals(2, fixture.acceptedConnections.get());
        }
    }

    @Test
    void oversizedChunkedResponseAbortsWithoutDrainingAndUsesNewConnection() throws Exception {
        ConsumptionFixture fixture = new ConsumptionFixture(true);
        try (fixture; CloseableHttpClient client = fixture.client();
                ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            ApacheWebhookHttpTransport transport = fixture.transport(client, scheduler, Duration.ofSeconds(2));
            WebhookHttpResponse first = transport.post(fixture.url("/chunked"), new byte[0], headers());
            assertTrue(first.responseBody().endsWith("[relay response truncated at 10240 bytes]"));
            WebhookHttpResponse second = transport.post(fixture.url("/chunked-next"), new byte[0], headers());
            assertEquals("ok", second.responseBody());
            assertTrue(fixture.limitReached.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(fixture.clientClosed.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(10_241, fixture.firstBytesWritten.get());
            assertEquals(2, fixture.acceptedConnections.get());
        }
    }

    @Test
    void partialBodyReadFailureDiscardsConnectionAndMapsTransportFailure() throws Exception {
        CountDownLatch secondServed = new CountDownLatch(1);
        AtomicInteger acceptedConnections = new AtomicInteger();
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
                CloseableHttpClient client = HttpClients.custom().disableRedirectHandling().disableAutomaticRetries()
                        .build();
                ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            Thread.startVirtualThread(() -> serveTruncatedChunkThenSuccess(server, acceptedConnections, secondServed));
            ApacheWebhookHttpTransport transport = new ApacheWebhookHttpTransport(client,
                    new BoundedApacheResponseBodyConsumer(), scheduler,
                    new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofSeconds(2));
            WebhookDeliveryException failure = assertThrows(WebhookDeliveryException.class,
                    () -> transport.post("http://127.0.0.1:" + server.getLocalPort() + "/broken", new byte[0], headers()));
            assertEquals(WebhookFailureCode.TRANSPORT_FAILURE, failure.failureCode());
            WebhookHttpResponse second = transport.post(
                    "http://127.0.0.1:" + server.getLocalPort() + "/after-broken", new byte[0], headers());
            assertEquals("ok", second.responseBody());
            assertTrue(secondServed.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(2, acceptedConnections.get());
        }
    }

    @Test
    void slowOversizedBodyReturnsBeforeProducerCanFinish() throws Exception {
        ConsumptionFixture fixture = new ConsumptionFixture(false);
        try (fixture; CloseableHttpClient client = fixture.client();
                ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
                ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
            ApacheWebhookHttpTransport transport = fixture.transport(client, scheduler, Duration.ofSeconds(2));
            long started = System.nanoTime();
            Future<WebhookHttpResponse> result = calls.submit(() -> transport.post(fixture.url("/slow"), new byte[0], headers()));
            WebhookHttpResponse response = result.get(1, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(response.responseBody().endsWith("[relay response truncated at 10240 bytes]"));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2_500);
            assertTrue(fixture.limitReached.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(fixture.clientClosed.await(1, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private static void serveTwoResponses(ServerSocket server, AtomicInteger acceptedConnections,
            CountDownLatch served) {
        try (Socket socket = server.accept();
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                        StandardCharsets.ISO_8859_1));
                OutputStream output = socket.getOutputStream()) {
            acceptedConnections.incrementAndGet();
            readHeaders(reader);
            writeResponse(output, "one");
            readHeaders(reader);
            writeResponse(output, "two");
            served.countDown();
        } catch (IOException ignored) {
            // Fixture teardown.
        }
    }

    private static void readHeaders(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            // Consume one request.
        }
    }

    private static void writeResponse(OutputStream output, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        output.write(("HTTP/1.1 200 OK\r\nContent-Length: " + bytes.length
                + "\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.flush();
    }

    private static WebhookHeaders headers() {
        return new WebhookHeaders("id", 1, "sig");
    }

    private static void serveTruncatedChunkThenSuccess(ServerSocket server, AtomicInteger acceptedConnections,
            CountDownLatch secondServed) {
        try (Socket first = server.accept()) {
            acceptedConnections.incrementAndGet();
            BufferedReader reader = new BufferedReader(new InputStreamReader(first.getInputStream(),
                    StandardCharsets.ISO_8859_1));
            readHeaders(reader);
            OutputStream output = first.getOutputStream();
            output.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: keep-alive\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            output.write("64\r\nshort".getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (IOException ignored) {
            // The client is expected to discard the broken first endpoint.
        }
        try (Socket second = server.accept();
                BufferedReader reader = new BufferedReader(new InputStreamReader(second.getInputStream(),
                        StandardCharsets.ISO_8859_1)); OutputStream output = second.getOutputStream()) {
            acceptedConnections.incrementAndGet();
            readHeaders(reader);
            writeResponse(output, "ok");
            secondServed.countDown();
        } catch (IOException ignored) {
            // Fixture teardown.
        }
    }

    private static final class ConsumptionFixture implements AutoCloseable {
        private final boolean chunked;
        private final ServerSocket server;
        private final CountDownLatch limitReached = new CountDownLatch(1);
        private final CountDownLatch clientClosed = new CountDownLatch(1);
        private final AtomicInteger acceptedConnections = new AtomicInteger();
        private final AtomicInteger firstBytesWritten = new AtomicInteger();

        private ConsumptionFixture(boolean chunked) throws IOException {
            this.chunked = chunked;
            this.server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            Thread.startVirtualThread(this::serve);
        }

        private CloseableHttpClient client() {
            return HttpClients.custom().disableRedirectHandling().disableAutomaticRetries().build();
        }

        private ApacheWebhookHttpTransport transport(CloseableHttpClient client,
                ScheduledExecutorService scheduler, Duration budget) {
            return new ApacheWebhookHttpTransport(client, new BoundedApacheResponseBodyConsumer(), scheduler,
                    new com.example.relay.endpoint.domain.WebhookUriParser(), budget);
        }

        private String url(String path) {
            return "http://127.0.0.1:" + server.getLocalPort() + path;
        }

        private void serve() {
            try (Socket first = server.accept()) {
                acceptedConnections.incrementAndGet();
                BufferedReader reader = new BufferedReader(new InputStreamReader(first.getInputStream(),
                        StandardCharsets.ISO_8859_1));
                readHeaders(reader);
                OutputStream output = first.getOutputStream();
                if (chunked) {
                    output.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: keep-alive\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                } else {
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 200000\r\nConnection: keep-alive\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                }
                output.flush();
                writeUntilLimitThenObserveClose(first, reader, output);
            } catch (IOException ignored) {
                // The client is expected to abort the oversized endpoint.
            }
            try (Socket second = server.accept();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(second.getInputStream(),
                            StandardCharsets.ISO_8859_1)); OutputStream output = second.getOutputStream()) {
                acceptedConnections.incrementAndGet();
                readHeaders(reader);
                writeResponse(output, "ok");
            } catch (IOException ignored) {
                // Fixture teardown.
            }
        }

        private void writeUntilLimitThenObserveClose(Socket socket, BufferedReader reader, OutputStream output)
                throws IOException {
            int written = 0;
            while (written < 10_241) {
                int chunk = Math.min(1_024, 10_241 - written);
                if (chunked) {
                    output.write(Integer.toHexString(chunk).getBytes(StandardCharsets.US_ASCII));
                    output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                output.write(new byte[chunk]);
                if (chunked) {
                    output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                output.flush();
                written += chunk;
            }
            firstBytesWritten.set(written);
            limitReached.countDown();
            socket.setSoTimeout(2_000);
            try {
                if (reader.read() == -1) {
                    clientClosed.countDown();
                }
            } catch (SocketTimeoutException ignored) {
                // Keep the fixture moving; the assertion below proves whether the client closed the stream.
            } catch (IOException exception) {
                clientClosed.countDown();
                throw exception;
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
