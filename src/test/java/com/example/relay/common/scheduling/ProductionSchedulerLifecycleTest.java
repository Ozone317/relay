package com.example.relay.common.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.deliveryengine.destination.DestinationPolicyBlockedException;
import com.example.relay.deliveryengine.http.ApacheWebhookHttpTransport;
import com.example.relay.deliveryengine.http.WebhookDeliveryException;
import com.example.relay.deliveryengine.http.WebhookFailureCode;
import com.example.relay.deliveryengine.http.WebhookHeaders;
import com.example.relay.deliveryengine.http.WebhookResponseBodyConsumer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.io.CloseMode;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ProductionSchedulerLifecycleTest {

    private static final WebhookHeaders HEADERS = new WebhookHeaders("id", 1, "sig");

    @Test
    void contextCloseOwnsAndIsolatesEachDeadlineScheduler() throws Exception {
        AnnotationConfigApplicationContext firstContext = schedulerContext();
        AnnotationConfigApplicationContext secondContext = schedulerContext();
        ThreadPoolTaskScheduler first = firstContext.getBean("webhookDeadlineTaskScheduler",
                ThreadPoolTaskScheduler.class);
        ThreadPoolTaskScheduler second = secondContext.getBean("webhookDeadlineTaskScheduler",
                ThreadPoolTaskScheduler.class);
        assertSchedulerTopology(first);

        CountDownLatch taskEntered = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        CountDownLatch taskInterrupted = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        first.schedule(() -> {
            taskEntered.countDown();
            try {
                releaseTask.await();
            } catch (InterruptedException expected) {
                taskInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
        }, Instant.now());
        assertTrue(taskEntered.await(1, TimeUnit.SECONDS));

        Thread closeThread = Thread.ofVirtual().start(() -> {
            closeStarted.countDown();
            try {
                firstContext.close();
            } catch (Throwable failure) {
                closeFailure.set(failure);
            }
        });
        try {
            assertTrue(closeStarted.await(1, TimeUnit.SECONDS));
            assertTrue(taskInterrupted.await(1, TimeUnit.SECONDS),
                    "closing the owning context must interrupt its running deadline task");
            closeThread.join(1000);
            assertFalse(closeThread.isAlive(), "owning context close did not finish");
            assertNull(closeFailure.get());
            assertTrue(first.getScheduledThreadPoolExecutor().isTerminated());
            assertThrows(RejectedExecutionException.class,
                    () -> first.schedule(() -> {}, Instant.now().plusSeconds(1)));

            CountDownLatch secondStillRuns = new CountDownLatch(1);
            second.schedule(secondStillRuns::countDown, Instant.now());
            assertTrue(secondStillRuns.await(1, TimeUnit.SECONDS),
                    "closing one context must leave the other context's scheduler operational");
            assertFalse(second.getScheduledThreadPoolExecutor().isShutdown());
        } finally {
            releaseTask.countDown();
            firstContext.close();
            secondContext.close();
            closeThread.join(6000);
        }
        assertTrue(first.getScheduledThreadPoolExecutor().isTerminated());
        assertTrue(second.getScheduledThreadPoolExecutor().isTerminated());
        assertEquals(0, first.getScheduledThreadPoolExecutor().getQueue().size());
        assertEquals(0, second.getScheduledThreadPoolExecutor().getQueue().size());
    }

    private static AnnotationConfigApplicationContext schedulerContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        context.register(ProductionSchedulerConfig.class);
        context.refresh();
        return context;
    }

    private static void assertSchedulerTopology(ThreadPoolTaskScheduler scheduler) {
        assertEquals(1, scheduler.getScheduledThreadPoolExecutor().getCorePoolSize());
        assertTrue(scheduler.getThreadNamePrefix().startsWith("relay-webhook-deadline-"));
        assertTrue(scheduler.isDaemon());
        assertTrue(scheduler.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy());
        assertFalse(scheduler.getScheduledThreadPoolExecutor().getExecuteExistingDelayedTasksAfterShutdownPolicy());
        assertFalse(scheduler.getScheduledThreadPoolExecutor().getContinueExistingPeriodicTasksAfterShutdownPolicy());
    }

    @Test
    void everyTerminalExchangePathCancelsItsReturnedDeadlineFuture() throws Exception {
        assertCancelledAfter(new RespondingClient(), input -> "ok", "http://webhook.test/hook", null);
        assertCancelledAfter(new DnsFailureClient(), input -> "", "http://webhook.test/hook",
                WebhookFailureCode.DNS_RESOLUTION_FAILED);
        assertCancelledAfter(new PolicyFailureClient(), input -> "", "http://webhook.test/hook",
                WebhookFailureCode.DESTINATION_POLICY_BLOCKED);
        assertCancelledAfter(new IoFailureClient(), input -> "", "http://webhook.test/hook",
                WebhookFailureCode.TRANSPORT_FAILURE);
        assertCancelledAfter(new TlsFailureClient(), input -> "", "http://webhook.test/hook",
                WebhookFailureCode.TRANSPORT_FAILURE);
        assertCancelledAfter(new RespondingClient(), input -> {
            throw new IOException("read failed");
        }, "http://webhook.test/hook", WebhookFailureCode.TRANSPORT_FAILURE);
    }

    @Test
    void timeoutCancellationAndFastCompletionRemoveTheScheduledEntryPromptly() throws Exception {
        TrackingScheduler scheduler = new TrackingScheduler();
        scheduler.initialize();
        try {
            int baseline = scheduler.getScheduledThreadPoolExecutor().getQueue().size();
            ApacheWebhookHttpTransport success = new ApacheWebhookHttpTransport(new RespondingClient(), input -> "",
                    scheduler);
            success.post("http://webhook.test/hook", new byte[0], HEADERS);
            assertEquals(baseline, scheduler.getScheduledThreadPoolExecutor().getQueue().size());
            assertEquals(1, scheduler.lastFuture.cancelCalls.get());
            assertFalse(scheduler.lastFuture.cancelArgument);

            scheduler.runScheduledTaskImmediately = true;
            ApacheWebhookHttpTransport timeout = new ApacheWebhookHttpTransport(new RespondingClient(), input -> "",
                    scheduler);
            FutureRun futureRun = new FutureRun(timeout);
            Thread thread = Thread.ofVirtual().start(futureRun);
            thread.join(1000);
            assertEquals(1, futureRun.cancelCalls.get());
            assertEquals(1, scheduler.lastFuture.cancelCalls.get());
            assertFalse(scheduler.lastFuture.cancelArgument);
            assertEquals(baseline, scheduler.getScheduledThreadPoolExecutor().getQueue().size());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void scheduleRejectionAfterCloseIsPropagatedWithoutAnUnownedFuture() {
        TrackingScheduler scheduler = new TrackingScheduler();
        scheduler.initialize();
        scheduler.shutdown();
        ApacheWebhookHttpTransport transport = new ApacheWebhookHttpTransport(new RespondingClient(), input -> "",
                scheduler);
        assertThrows(RejectedExecutionException.class,
                () -> transport.post("http://webhook.test/hook", new byte[0], HEADERS));
        assertEquals(1, scheduler.scheduled.get());
        assertNull(scheduler.lastFuture);
    }

    private static void assertCancelledAfter(CloseableHttpClient client, WebhookResponseBodyConsumer consumer,
            String uri, WebhookFailureCode expectedFailure) throws Exception {
        TrackingScheduler scheduler = new TrackingScheduler();
        scheduler.initialize();
        try {
            ApacheWebhookHttpTransport transport = new ApacheWebhookHttpTransport(client, consumer, scheduler);
            if (expectedFailure == null) {
                assertEquals(204, transport.post(uri, new byte[0], HEADERS).statusCode());
            } else {
                WebhookDeliveryException failure = assertThrows(WebhookDeliveryException.class,
                        () -> transport.post(uri, new byte[0], HEADERS));
                assertEquals(expectedFailure, failure.failureCode());
            }
            assertNotNull(scheduler.lastFuture);
            assertEquals(1, scheduler.lastFuture.cancelCalls.get());
            assertFalse(scheduler.lastFuture.cancelArgument);
        } finally {
            scheduler.shutdown();
        }
    }

    private static final class TrackingScheduler extends ThreadPoolTaskScheduler {
        private final AtomicInteger scheduled = new AtomicInteger();
        private volatile TrackingFuture lastFuture;
        private volatile boolean runScheduledTaskImmediately;

        private TrackingScheduler() {
            setRemoveOnCancelPolicy(true);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            scheduled.incrementAndGet();
            if (runScheduledTaskImmediately) {
                task.run();
            }
            ScheduledFuture<?> delegate = super.schedule(task, startTime);
            lastFuture = new TrackingFuture(delegate);
            return lastFuture;
        }
    }

    private static final class TrackingFuture implements ScheduledFuture<Object> {
        private final ScheduledFuture<?> delegate;
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private volatile boolean cancelArgument;

        private TrackingFuture(ScheduledFuture<?> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelArgument = mayInterruptIfRunning;
            cancelCalls.incrementAndGet();
            return delegate.cancel(mayInterruptIfRunning);
        }

        @Override public boolean isCancelled() { return delegate.isCancelled(); }
        @Override public boolean isDone() { return delegate.isDone(); }
        @Override public Object get() throws java.util.concurrent.ExecutionException, InterruptedException { return delegate.get(); }
        @Override public Object get(long timeout, TimeUnit unit) throws java.util.concurrent.ExecutionException,
                InterruptedException, java.util.concurrent.TimeoutException { return delegate.get(timeout, unit); }
        @Override public long getDelay(TimeUnit unit) { return delegate.getDelay(unit); }
        @Override public int compareTo(java.util.concurrent.Delayed other) { return delegate.compareTo(other); }
    }

    private static final class FutureRun implements Runnable {
        private final ApacheWebhookHttpTransport transport;
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private FutureRun(ApacheWebhookHttpTransport transport) { this.transport = transport; }
        @Override public void run() {
            try {
                transport.post("http://webhook.test/hook", new byte[0], HEADERS);
            } catch (WebhookDeliveryException expected) {
                cancelCalls.incrementAndGet();
            }
        }
    }

    private static final class RespondingClient extends CloseableHttpClient {
        @Override protected CloseableHttpResponse doExecute(org.apache.hc.core5.http.HttpHost target,
                ClassicHttpRequest request, org.apache.hc.core5.http.protocol.HttpContext context) {
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(204);
            response.setEntity(new InputStreamEntity(java.io.InputStream.nullInputStream(), 0, null));
            return CloseableHttpResponse.adapt(response);
        }
        @Override public void close(CloseMode mode) {}
        @Override public void close() {}
    }

    private static final class DnsFailureClient extends CloseableHttpClient {
        @Override protected CloseableHttpResponse doExecute(org.apache.hc.core5.http.HttpHost target,
                ClassicHttpRequest request, org.apache.hc.core5.http.protocol.HttpContext context) throws IOException {
            throw new UnknownHostException("missing");
        }
        @Override public void close(CloseMode mode) {}
        @Override public void close() {}
    }

    private static final class PolicyFailureClient extends CloseableHttpClient {
        @Override protected CloseableHttpResponse doExecute(org.apache.hc.core5.http.HttpHost target,
                ClassicHttpRequest request, org.apache.hc.core5.http.protocol.HttpContext context) {
            throw new DestinationPolicyBlockedException("blocked");
        }
        @Override public void close(CloseMode mode) {}
        @Override public void close() {}
    }

    private static final class IoFailureClient extends CloseableHttpClient {
        @Override protected CloseableHttpResponse doExecute(org.apache.hc.core5.http.HttpHost target,
                ClassicHttpRequest request, org.apache.hc.core5.http.protocol.HttpContext context) throws IOException {
            throw new IOException("connect failed");
        }
        @Override public void close(CloseMode mode) {}
        @Override public void close() {}
    }

    private static final class TlsFailureClient extends CloseableHttpClient {
        @Override protected CloseableHttpResponse doExecute(org.apache.hc.core5.http.HttpHost target,
                ClassicHttpRequest request, org.apache.hc.core5.http.protocol.HttpContext context) throws IOException {
            throw new SSLHandshakeException("TLS negotiation failed");
        }
        @Override public void close(CloseMode mode) {}
        @Override public void close() {}
    }
}
