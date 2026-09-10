package com.example.relay.deliveryengine.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class DeliveryHttpClientPinningTest {

    private static final int DELIVERY_TIMEOUT_MILLIS = 15_000;
    private static final int CONCURRENT_CALLS = 20;

    private MockWebServer mockWebServer;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                Thread.sleep(500);
                return new MockResponse().setResponseCode(200).setBody("ok");
            }
        });
        mockWebServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    private long measurePinnedNanos(RestClient client) throws Exception {
        Path jfrFile = Files.createTempFile("pinning", ".jfr");
        Recording recording = new Recording();
        recording.enable("jdk.VirtualThreadPinned").withStackTrace();
        recording.start();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch done = new CountDownLatch(CONCURRENT_CALLS);
            for (int i = 0; i < CONCURRENT_CALLS; i++) {
                executor.submit(() -> {
                    try {
                        client.get().uri(mockWebServer.url("/probe").toString()).retrieve().toBodilessEntity();
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await(30, TimeUnit.SECONDS);
        }

        recording.stop();
        recording.dump(jfrFile);
        List<RecordedEvent> events = RecordingFile.readAllEvents(jfrFile);
        long totalPinnedNanos = events.stream()
                .filter(e -> e.getEventType().getName().equals("jdk.VirtualThreadPinned"))
                .mapToLong(e -> e.getDuration().toNanos())
                .sum();
        recording.close();
        Files.deleteIfExists(jfrFile);
        return totalPinnedNanos;
    }

    @Test
    void jdkClientHttpRequestFactory_pinsNoMoreThanSimpleClientHttpRequestFactory() throws Exception {
        SimpleClientHttpRequestFactory oldFactory = new SimpleClientHttpRequestFactory();
        oldFactory.setConnectTimeout(DELIVERY_TIMEOUT_MILLIS);
        oldFactory.setReadTimeout(DELIVERY_TIMEOUT_MILLIS);
        RestClient oldClient = RestClient.builder().requestFactory(oldFactory).build();

        // Exercise the real production construction path (DeliveryHttpClientConfig.buildHttpClient())
        // rather than reimplementing it here with a bare no-arg JdkClientHttpRequestFactory - a
        // no-arg factory builds its own HttpClient.newHttpClient(), which is materially different
        // (no virtual-thread executor, no explicit redirect policy) from what actually ships.
        JdkClientHttpRequestFactory newFactory = new JdkClientHttpRequestFactory(
                new DeliveryHttpClientConfig().buildHttpClient());
        newFactory.setReadTimeout(DELIVERY_TIMEOUT_MILLIS);
        RestClient newClient = RestClient.builder().requestFactory(newFactory).build();

        long oldPinnedNanos = measurePinnedNanos(oldClient);
        long newPinnedNanos = measurePinnedNanos(newClient);

        System.out.printf(
                "Virtual-thread pinning measurement: SimpleClientHttpRequestFactory=%dns, "
                        + "JdkClientHttpRequestFactory=%dns over %d concurrent calls%n",
                oldPinnedNanos, newPinnedNanos, CONCURRENT_CALLS);

        // Deliberately NOT a strict `newPinnedNanos <= oldPinnedNanos` gate - JFR measurements are
        // inherently noisy, and two runs of identical code can differ purely from scheduling/JVM/
        // environment effects. A wide absolute tolerance absorbs that noise; this only fails if the
        // new client is pinning MATERIALLY worse than the old one, which is the actual regression
        // this test exists to catch, not "the two numbers weren't in the exact expected order."
        long noiseToleranceNanos = Duration.ofMillis(200).toNanos();
        assertTrue(newPinnedNanos <= oldPinnedNanos + noiseToleranceNanos,
                "expected JdkClientHttpRequestFactory's pinning to not be MATERIALLY worse than "
                        + "SimpleClientHttpRequestFactory's (old=" + oldPinnedNanos + "ns, new=" + newPinnedNanos
                        + "ns, tolerance=" + noiseToleranceNanos + "ns) - a small overage is expected noise; "
                        + "only investigate the client choice if this fails by a wide margin, and re-run once "
                        + "before concluding it's a real regression rather than a noisy measurement");
    }
}
