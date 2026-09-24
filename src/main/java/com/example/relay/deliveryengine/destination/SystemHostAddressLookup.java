package com.example.relay.deliveryengine.destination;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.example.relay.deliveryengine.config.DeliveryDnsProperties;
import com.example.relay.deliveryengine.http.DeliveryDeadline;

public class SystemHostAddressLookup implements HostAddressLookup, AutoCloseable {

    private final DeliveryDnsProperties properties;
    private final ExecutorService executor;
    private final Function<String, InetAddress[]> platformLookup;
    private final boolean ownsExecutor;

    public SystemHostAddressLookup(DeliveryDnsProperties properties) {
        this(properties, newExecutor(properties), SystemHostAddressLookup::lookupPlatform, true);
    }

    public SystemHostAddressLookup(DeliveryDnsProperties properties, ThreadPoolExecutor executor) {
        this(properties, executor, SystemHostAddressLookup::lookupPlatform);
    }

    public SystemHostAddressLookup(DeliveryDnsProperties properties, Function<String, InetAddress[]> platformLookup) {
        this(properties, newExecutor(properties), platformLookup, true);
    }

    public SystemHostAddressLookup(DeliveryDnsProperties properties, ExecutorService executor,
            Function<String, InetAddress[]> platformLookup) {
        this(properties, executor, platformLookup, false);
    }

    private SystemHostAddressLookup(DeliveryDnsProperties properties, ExecutorService executor,
            Function<String, InetAddress[]> platformLookup, boolean ownsExecutor) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.properties.validate();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.platformLookup = Objects.requireNonNull(platformLookup, "platformLookup");
        this.ownsExecutor = ownsExecutor;
    }

    @Override
    public List<InetAddress> lookup(String absoluteHostname, DeliveryDeadline deadline) throws DnsResolutionException {
        if (absoluteHostname == null || absoluteHostname.isBlank() || !absoluteHostname.endsWith(".")
                || absoluteHostname.endsWith("..")) {
            throw new DnsResolutionException("DNS lookup requires one absolute terminating dot");
        }
        Objects.requireNonNull(deadline, "deadline");
        Duration remaining = deadline.remaining();
        if (remaining.isZero() || remaining.isNegative()) {
            throw new DnsResolutionException("Delivery deadline expired before DNS lookup");
        }
        Duration waitBudget = remaining.compareTo(properties.getTimeout()) < 0 ? remaining : properties.getTimeout();
        Future<InetAddress[]> future;
        try {
            future = executor.submit(() -> platformLookup.apply(absoluteHostname));
        } catch (RejectedExecutionException exception) {
            throw new DnsResolutionException("DNS resolver executor is saturated", exception);
        }
        try {
            InetAddress[] result = future.get(waitBudget.toNanos(), TimeUnit.NANOSECONDS);
            if (deadline.remaining().isZero() || deadline.remaining().isNegative()) {
                throw new DnsResolutionException("Delivery deadline expired during DNS lookup");
            }
            if (result == null || result.length == 0 || Arrays.stream(result).anyMatch(Objects::isNull)) {
                throw new DnsResolutionException("DNS lookup returned no usable addresses");
            }
            return List.of(result);
        } catch (java.util.concurrent.TimeoutException exception) {
            future.cancel(true);
            throw new DnsResolutionException("DNS lookup timed out", exception);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DnsResolutionException("DNS lookup interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            throw new DnsResolutionException("DNS lookup failed", cause);
        } catch (java.util.concurrent.CancellationException exception) {
            throw new DnsResolutionException("DNS lookup was cancelled", exception);
        }
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    private static InetAddress[] lookupPlatform(String hostname) {
        try {
            return InetAddress.getAllByName(hostname);
        } catch (UnknownHostException exception) {
            throw new PlatformLookupException(exception);
        }
    }

    private static ThreadPoolExecutor newExecutor(DeliveryDnsProperties properties) {
        properties.validate();
        return new ThreadPoolExecutor(properties.getMaxConcurrency(), properties.getMaxConcurrency(), 0L,
                TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(properties.getQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "relay-delivery-dns");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    private static final class PlatformLookupException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private PlatformLookupException(UnknownHostException cause) {
            super(cause);
        }
    }
}
