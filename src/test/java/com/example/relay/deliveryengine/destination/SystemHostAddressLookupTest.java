package com.example.relay.deliveryengine.destination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.example.relay.deliveryengine.config.DeliveryDnsProperties;
import com.example.relay.deliveryengine.http.DeliveryDeadline;

class SystemHostAddressLookupTest {

    @Test
    void returnsPlatformAddressesForAnAbsoluteHostname() throws Exception {
        DeliveryDnsProperties properties = properties(1, 1, Duration.ofSeconds(1));
        try (SystemHostAddressLookup lookup = new SystemHostAddressLookup(properties,
                ignored -> new InetAddress[] { addressUnchecked("93.184.216.34") })) {
            assertEquals("93.184.216.34", lookup.lookup("hooks.example.test.",
                    DeliveryDeadline.start(Duration.ofSeconds(1), System::nanoTime)).get(0).getHostAddress());
        }
    }

    @Test
    void nullAndEmptyPlatformResultsFailClosed() throws Exception {
        DeliveryDnsProperties properties = properties(1, 1, Duration.ofSeconds(1));
        try (SystemHostAddressLookup nullLookup = new SystemHostAddressLookup(properties, ignored -> null)) {
            assertThrows(DnsResolutionException.class,
                    () -> nullLookup.lookup("hooks.example.test.", deadline(1)));
        }
        try (SystemHostAddressLookup emptyLookup = new SystemHostAddressLookup(properties, ignored -> new InetAddress[0])) {
            assertThrows(DnsResolutionException.class,
                    () -> emptyLookup.lookup("hooks.example.test.", deadline(1)));
        }
    }

    @Test
    void timeoutCancelsLatePlatformResultAndReturnsNoCandidates() throws Exception {
        DeliveryDnsProperties properties = properties(1, 1, Duration.ofMillis(20));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean();
        try (SystemHostAddressLookup lookup = new SystemHostAddressLookup(properties, ignored -> {
            started.countDown();
            await(release);
            completed.set(true);
            return new InetAddress[] { addressUnchecked("93.184.216.34") };
        })) {
            assertThrows(DnsResolutionException.class, () -> lookup.lookup("hooks.example.test.", deadline(1)));
            assertFalse(completed.get());
            release.countDown();
            assertEquals(true, started.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void interruptionRestoresInterruptFlagAndFailsClosed() throws Exception {
        DeliveryDnsProperties properties = properties(1, 1, Duration.ofSeconds(1));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (SystemHostAddressLookup lookup = new SystemHostAddressLookup(properties, ignored -> {
            started.countDown();
            await(release);
            return new InetAddress[] { addressUnchecked("93.184.216.34") };
        })) {
            Thread thread = new Thread(() -> {
                assertThrows(DnsResolutionException.class,
                        () -> lookup.lookup("hooks.example.test.", deadline(1)));
                assertEquals(true, Thread.currentThread().isInterrupted());
            });
            thread.start();
            assertEquals(true, started.await(1, TimeUnit.SECONDS));
            thread.interrupt();
            release.countDown();
            thread.join(1_000L);
            assertFalse(thread.isAlive());
        }
    }

    @Test
    void configuredConcurrencyAndQueueCapacityRejectSaturationAndRecover() throws Exception {
        DeliveryDnsProperties properties = properties(1, 1, Duration.ofSeconds(2));
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger platformCalls = new AtomicInteger();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        SystemHostAddressLookup lookup = new SystemHostAddressLookup(properties, executor, ignored -> {
            if (platformCalls.incrementAndGet() == 1) {
                firstStarted.countDown();
                await(releaseFirst);
            }
            return new InetAddress[] { addressUnchecked("93.184.216.34") };
        });
        try {
            Thread first = new Thread(() -> invokeLookup(lookup, workerFailure));
            first.start();
            assertEquals(true, firstStarted.await(1, TimeUnit.SECONDS));

            Thread second = new Thread(() -> invokeLookup(lookup, workerFailure));
            second.start();
            while (executor.getQueue().size() != 1) {
                Thread.onSpinWait();
            }

            assertThrows(DnsResolutionException.class,
                    () -> lookup.lookup("hooks.example.test.", deadline(1)));

            releaseFirst.countDown();
            first.join(1_000L);
            second.join(1_000L);
            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
            assertEquals(null, workerFailure.get());
            assertEquals("93.184.216.34",
                    lookup.lookup("hooks.example.test.", deadline(1)).get(0).getHostAddress());
        } finally {
            releaseFirst.countDown();
            lookup.close();
            executor.shutdownNow();
        }
    }

    private static void invokeLookup(SystemHostAddressLookup lookup, AtomicReference<Throwable> failure) {
        try {
            lookup.lookup("hooks.example.test.", deadline(1));
        } catch (Throwable exception) {
            failure.compareAndSet(null, exception);
        }
    }

    private static DeliveryDnsProperties properties(int workers, int queue, Duration timeout) {
        DeliveryDnsProperties result = new DeliveryDnsProperties();
        result.setMaxConcurrency(workers);
        result.setQueueCapacity(queue);
        result.setTimeout(timeout);
        result.validate();
        return result;
    }

    private static DeliveryDeadline deadline(long seconds) {
        return DeliveryDeadline.start(Duration.ofSeconds(seconds), System::nanoTime);
    }

    private static InetAddress address(String value) throws UnknownHostException {
        return InetAddress.getByName(value);
    }

    private static InetAddress addressUnchecked(String value) {
        try {
            return address(value);
        } catch (UnknownHostException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
