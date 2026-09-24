package com.example.relay.deliveryengine.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.io.CloseMode;
import org.junit.jupiter.api.Test;

class ApacheWebhookHttpTransportDeadlineTest {

    @Test
    void clearsDeadlineContextAfterUnexpectedExchangeException() throws Exception {
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            ApacheWebhookHttpTransport transport = new ApacheWebhookHttpTransport(new ThrowingClient(), input -> "",
                    scheduler, new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofMillis(100));

            assertThrows(IllegalStateException.class, () -> transport.post("http://webhook.test/hook", new byte[0],
                    new WebhookHeaders("id", 1, "sig")));
            assertThrows(IllegalStateException.class, DeliveryDeadlineContext::current);
        }
    }

    @Test
    void mapsResponseConsumerStallToDeadlineTimeoutAndClearsContext() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            ApacheWebhookHttpTransport transport = new ApacheWebhookHttpTransport(
                    new ResponseClient(), input -> {
                        try {
                            Thread.sleep(Duration.ofSeconds(10));
                        } catch (InterruptedException exception) {
                            cancelled.set(true);
                            Thread.currentThread().interrupt();
                        }
                        return "late";
                    }, scheduler, new com.example.relay.endpoint.domain.WebhookUriParser(), Duration.ofMillis(50));

            WebhookDeliveryException exception = assertThrows(WebhookDeliveryException.class,
                    () -> transport.post("http://webhook.test/hook", new byte[0],
                            new WebhookHeaders("id", 1, "sig")));
            assertEquals(WebhookFailureCode.DELIVERY_TIMEOUT, exception.failureCode());
            long waitUntil = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (!cancelled.get() && System.nanoTime() < waitUntil) {
                Thread.onSpinWait();
            }
            assertEquals(true, cancelled.get());
            assertThrows(IllegalStateException.class, DeliveryDeadlineContext::current);
        }
    }

    @Test
    void mapsExpectedResolverFailuresWithoutLeavingDeadlineScope() throws Exception {
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            ApacheWebhookHttpTransport dnsTransport = new ApacheWebhookHttpTransport(new DnsFailureClient(),
                    input -> "", scheduler, new com.example.relay.endpoint.domain.WebhookUriParser(),
                    Duration.ofMillis(100));
            WebhookDeliveryException dns = assertThrows(WebhookDeliveryException.class,
                    () -> dnsTransport.post("http://webhook.test/hook", new byte[0],
                            new WebhookHeaders("id", 1, "sig")));
            assertEquals(WebhookFailureCode.DNS_RESOLUTION_FAILED, dns.failureCode());
            assertThrows(IllegalStateException.class, DeliveryDeadlineContext::current);

            ApacheWebhookHttpTransport policyTransport = new ApacheWebhookHttpTransport(new PolicyFailureClient(),
                    input -> "", scheduler, new com.example.relay.endpoint.domain.WebhookUriParser(),
                    Duration.ofMillis(100));
            WebhookDeliveryException policy = assertThrows(WebhookDeliveryException.class,
                    () -> policyTransport.post("http://webhook.test/hook", new byte[0],
                            new WebhookHeaders("id", 1, "sig")));
            assertEquals(WebhookFailureCode.DESTINATION_POLICY_BLOCKED, policy.failureCode());
            assertThrows(IllegalStateException.class, DeliveryDeadlineContext::current);
        }
    }

    @Test
    void keepsNormalizedHostnameAsHttpAndTlsRouteIdentity() throws Exception {
        AtomicReference<org.apache.hc.core5.http.HttpHost> target = new AtomicReference<>();
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            ApacheWebhookHttpTransport transport = new ApacheWebhookHttpTransport(new RecordingClient(target),
                    input -> "", scheduler, new com.example.relay.endpoint.domain.WebhookUriParser(),
                    Duration.ofMillis(100));
            transport.post("HTTP://Hooks.Example.Test:8080/path", new byte[0],
                    new WebhookHeaders("id", 1, "sig"));
        }
        assertEquals("hooks.example.test", target.get().getHostName());
        org.junit.jupiter.api.Assertions.assertNull(target.get().getAddress());
    }

    private static final class ThrowingClient extends CloseableHttpClient {
        @Override
        protected CloseableHttpResponse doExecute(org.apache.hc.core5.http.HttpHost target,
                ClassicHttpRequest request, org.apache.hc.core5.http.protocol.HttpContext context) throws IOException {
            throw new IllegalStateException("invariant failure");
        }

        @Override
        public void close(CloseMode closeMode) {
        }

        @Override
        public void close() {
        }
    }

    private static final class ResponseClient extends CloseableHttpClient {
        @Override
        protected CloseableHttpResponse doExecute(org.apache.hc.core5.http.HttpHost target,
                ClassicHttpRequest request, org.apache.hc.core5.http.protocol.HttpContext context) {
            ClassicHttpResponse response = new BasicClassicHttpResponse(200);
            return CloseableHttpResponse.adapt(response);
        }

        @Override
        public void close(CloseMode closeMode) {
        }

        @Override
        public void close() {
        }
    }

    private static final class DnsFailureClient extends CloseableHttpClient {
        @Override
        protected CloseableHttpResponse doExecute(org.apache.hc.core5.http.HttpHost target,
                ClassicHttpRequest request, org.apache.hc.core5.http.protocol.HttpContext context)
                throws IOException {
            throw new UnknownHostException("host unavailable");
        }

        @Override
        public void close(CloseMode closeMode) {
        }

        @Override
        public void close() {
        }
    }

    private static final class PolicyFailureClient extends CloseableHttpClient {
        @Override
        protected CloseableHttpResponse doExecute(org.apache.hc.core5.http.HttpHost target,
                ClassicHttpRequest request, org.apache.hc.core5.http.protocol.HttpContext context) {
            throw new com.example.relay.deliveryengine.destination.DestinationPolicyBlockedException("LOOPBACK");
        }

        @Override
        public void close(CloseMode closeMode) {
        }

        @Override
        public void close() {
        }
    }

    private static final class RecordingClient extends CloseableHttpClient {
        private final AtomicReference<org.apache.hc.core5.http.HttpHost> target;

        private RecordingClient(AtomicReference<org.apache.hc.core5.http.HttpHost> target) {
            this.target = target;
        }

        @Override
        protected CloseableHttpResponse doExecute(org.apache.hc.core5.http.HttpHost target,
                ClassicHttpRequest request, org.apache.hc.core5.http.protocol.HttpContext context) {
            this.target.set(target);
            return CloseableHttpResponse.adapt(new BasicClassicHttpResponse(204));
        }

        @Override
        public void close(CloseMode closeMode) {
        }

        @Override
        public void close() {
        }
    }
}
