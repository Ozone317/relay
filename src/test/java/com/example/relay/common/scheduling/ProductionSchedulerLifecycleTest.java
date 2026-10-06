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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLHandshakeException;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.io.CloseMode;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ProductionSchedulerLifecycleTest {

    private static final WebhookHeaders HEADERS = new WebhookHeaders("id", 1, "sig");
    private static final List<SchedulerSpec> REAL_SCHEDULERS = List.of(
            new SchedulerSpec(SchedulerNames.DELIVERY_PROGRESS, 2, "relay-delivery-progress-"),
            new SchedulerSpec(SchedulerNames.DELIVERY_RECONCILIATION, 1, "relay-delivery-reconciliation-"),
            new SchedulerSpec(SchedulerNames.PASSWORD_RESET_MAINTENANCE, 2, "relay-password-reset-maintenance-"),
            new SchedulerSpec(SchedulerNames.WEBHOOK_DEADLINE, 1, "relay-webhook-deadline-"));

    @Test
    void allRealSchedulersUseApprovedShutdownFlags() {
        AnnotationConfigApplicationContext context = schedulerContext();
        try {
            assertSchedulerShutdownFlags(schedulers(context));
        } finally {
            context.close();
        }
    }

    @Test
    void contextCloseOwnsAndIsolatesEachDeadlineScheduler() throws Exception {
        AnnotationConfigApplicationContext firstContext = schedulerContext();
        AnnotationConfigApplicationContext secondContext = schedulerContext();
        Map<String, ThreadPoolTaskScheduler> firstSchedulers = schedulers(firstContext);
        Map<String, ThreadPoolTaskScheduler> secondSchedulers = schedulers(secondContext);
        assertSchedulerTopology(firstSchedulers);

        CountDownLatch tasksEntered = new CountDownLatch(REAL_SCHEDULERS.size());
        CountDownLatch releaseTask = new CountDownLatch(1);
        Map<String, CountDownLatch> taskInterrupted = new LinkedHashMap<>();
        List<ScheduledFuture<?>> queuedTasks = new ArrayList<>();
        CountDownLatch queuedTasksRan = new CountDownLatch(REAL_SCHEDULERS.size());
        for (SchedulerSpec spec : REAL_SCHEDULERS) {
            ThreadPoolTaskScheduler scheduler = firstSchedulers.get(spec.name());
            CountDownLatch interrupted = new CountDownLatch(1);
            taskInterrupted.put(spec.name(), interrupted);
            scheduler.schedule(() -> {
                tasksEntered.countDown();
                try {
                    releaseTask.await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            }, Instant.now());
            queuedTasks.add(scheduler.schedule(queuedTasksRan::countDown, Instant.now().plusSeconds(3600)));
        }
        assertTrue(tasksEntered.await(1, TimeUnit.SECONDS));

        CountDownLatch closeStarted = new CountDownLatch(1);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
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
            assertTrue(awaitCondition(() -> firstSchedulers.values().stream()
                    .allMatch(scheduler -> scheduler.getScheduledThreadPoolExecutor().isShutdown()), 500),
                    "all real schedulers must reject new work as context close begins");
            for (SchedulerSpec spec : REAL_SCHEDULERS) {
                ThreadPoolTaskScheduler scheduler = firstSchedulers.get(spec.name());
                assertThrows(RejectedExecutionException.class,
                        () -> scheduler.schedule(() -> {}, Instant.now().plusSeconds(1)),
                        spec.name() + " accepted work after context close began");
            }
            assertTrue(queuedTasks.stream().allMatch(ScheduledFuture::isCancelled),
                    "queued delayed work must be canceled at early shutdown");
            assertEquals((long) REAL_SCHEDULERS.size(), queuedTasksRan.getCount(),
                    "queued work must not run after close begins");
            for (SchedulerSpec spec : REAL_SCHEDULERS) {
                assertEquals(0, firstSchedulers.get(spec.name()).getScheduledThreadPoolExecutor().getQueue().size());
            }

            for (SchedulerSpec spec : REAL_SCHEDULERS) {
                assertTrue(taskInterrupted.get(spec.name()).await(3, TimeUnit.SECONDS),
                        () -> spec.name() + " was not interrupted during bean destruction");
            }
            closeThread.join(3000);
            assertFalse(closeThread.isAlive(), "owning context close did not finish");
            assertNull(closeFailure.get());
            for (SchedulerSpec spec : REAL_SCHEDULERS) {
                ThreadPoolTaskScheduler scheduler = firstSchedulers.get(spec.name());
                assertTrue(scheduler.getScheduledThreadPoolExecutor().isTerminated());
                assertThrows(RejectedExecutionException.class,
                        () -> scheduler.schedule(() -> {}, Instant.now().plusSeconds(1)));
            }

            CountDownLatch secondContextRuns = new CountDownLatch(REAL_SCHEDULERS.size());
            for (SchedulerSpec spec : REAL_SCHEDULERS) {
                secondSchedulers.get(spec.name()).schedule(secondContextRuns::countDown, Instant.now());
            }
            assertTrue(secondContextRuns.await(1, TimeUnit.SECONDS),
                    "closing one context must leave every scheduler in the other context operational");
            assertTrue(secondSchedulers.values().stream()
                    .noneMatch(scheduler -> scheduler.getScheduledThreadPoolExecutor().isShutdown()));
        } finally {
            releaseTask.countDown();
            closeThread.join(6000);
            firstContext.close();
            secondContext.close();
        }
        assertTrue(firstSchedulers.values().stream()
                .allMatch(scheduler -> scheduler.getScheduledThreadPoolExecutor().isTerminated()));
        assertTrue(secondSchedulers.values().stream()
                .allMatch(scheduler -> scheduler.getScheduledThreadPoolExecutor().isTerminated()));
        assertEquals((long) REAL_SCHEDULERS.size(), queuedTasksRan.getCount());
    }

    private static AnnotationConfigApplicationContext schedulerContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        context.registerBean("lifecycleProcessor", DefaultLifecycleProcessor.class, () -> {
            DefaultLifecycleProcessor processor = new DefaultLifecycleProcessor();
            processor.setTimeoutPerShutdownPhase(1500);
            return processor;
        });
        context.register(ProductionSchedulerConfig.class);
        context.refresh();
        return context;
    }

    private static Map<String, ThreadPoolTaskScheduler> schedulers(AnnotationConfigApplicationContext context) {
        Map<String, ThreadPoolTaskScheduler> schedulers = new LinkedHashMap<>();
        for (SchedulerSpec spec : REAL_SCHEDULERS) {
            schedulers.put(spec.name(), context.getBean(spec.name(), ThreadPoolTaskScheduler.class));
        }
        return schedulers;
    }

    private static void assertSchedulerTopology(Map<String, ThreadPoolTaskScheduler> schedulers) {
        for (SchedulerSpec spec : REAL_SCHEDULERS) {
            ThreadPoolTaskScheduler scheduler = schedulers.get(spec.name());
            assertEquals(spec.poolSize(), scheduler.getScheduledThreadPoolExecutor().getCorePoolSize());
            assertTrue(scheduler.getThreadNamePrefix().startsWith(spec.threadPrefix()));
            assertTrue(scheduler.isDaemon());
            assertTrue(scheduler.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy());
            assertFalse(scheduler.getScheduledThreadPoolExecutor()
                    .getExecuteExistingDelayedTasksAfterShutdownPolicy(),
                    spec.name() + " executes delayed work after shutdown");
            assertFalse(scheduler.getScheduledThreadPoolExecutor()
                    .getContinueExistingPeriodicTasksAfterShutdownPolicy(),
                    spec.name() + " continues periodic work after shutdown");
        }
    }

    private static void assertSchedulerShutdownFlags(Map<String, ThreadPoolTaskScheduler> schedulers) {
        for (SchedulerSpec spec : REAL_SCHEDULERS) {
            ThreadPoolTaskScheduler scheduler = schedulers.get(spec.name());
            assertFalse(schedulerFlag(scheduler, "acceptTasksAfterContextClose"),
                    spec.name() + " accepts tasks after context close");
            assertFalse(schedulerFlag(scheduler, "waitForTasksToCompleteOnShutdown"),
                    spec.name() + " waits for tasks to complete during shutdown");
        }
    }

    private static boolean schedulerFlag(ThreadPoolTaskScheduler scheduler, String fieldName) {
        try {
            Field field = org.springframework.scheduling.concurrent.ExecutorConfigurationSupport.class
                    .getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getBoolean(scheduler);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not inspect scheduler flag " + fieldName, failure);
        }
    }

    private static boolean awaitCondition(BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.yield();
        }
        return condition.getAsBoolean();
    }

    private record SchedulerSpec(String name, int poolSize, String threadPrefix) {
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
