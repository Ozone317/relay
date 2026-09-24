package com.example.relay.deliveryengine.http;

import com.example.relay.deliveryengine.destination.DnsResolutionException;
import com.example.relay.deliveryengine.destination.HostAddressLookup;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Stateful DNS fixture used to prove the address set consumed by real sockets. */
final class ControllableHostAddressLookup implements HostAddressLookup {

    private final Queue<List<InetAddress>> answers = new ArrayDeque<>();
    private final List<String> absoluteHostnames = new ArrayList<>();
    private final AtomicInteger invocations = new AtomicInteger();
    private final Object monitor = new Object();
    private volatile InvocationGate gate;

    ControllableHostAddressLookup enqueue(InetAddress... addresses) {
        return enqueue(List.of(addresses));
    }

    ControllableHostAddressLookup enqueue(List<InetAddress> addresses) {
        Objects.requireNonNull(addresses, "addresses");
        synchronized (monitor) {
            answers.add(List.copyOf(addresses));
        }
        return this;
    }

    InvocationGate blockNextInvocation() {
        InvocationGate next = new InvocationGate();
        gate = next;
        return next;
    }

    int invocationCount() {
        return invocations.get();
    }

    List<String> absoluteHostnames() {
        synchronized (monitor) {
            return List.copyOf(absoluteHostnames);
        }
    }

    @Override
    public List<InetAddress> lookup(String absoluteHostname, DeliveryDeadline deadline) throws DnsResolutionException {
        invocations.incrementAndGet();
        synchronized (monitor) {
            absoluteHostnames.add(absoluteHostname);
        }
        InvocationGate currentGate = gate;
        if (currentGate != null && currentGate.claim()) {
            currentGate.awaitRelease();
        }
        synchronized (monitor) {
            if (answers.isEmpty()) {
                throw new DnsResolutionException("test lookup has no queued answer");
            }
            return answers.remove();
        }
    }

    static final class InvocationGate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private boolean claimed;

        private synchronized boolean claim() {
            if (claimed) {
                return false;
            }
            claimed = true;
            entered.countDown();
            return true;
        }

        private void awaitRelease() throws DnsResolutionException {
            try {
                if (!released.await(5, TimeUnit.SECONDS)) {
                    throw new DnsResolutionException("test lookup was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new DnsResolutionException("test lookup interrupted");
            }
        }

        void awaitEntered() throws InterruptedException {
            if (!entered.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("lookup was not invoked");
            }
        }

        void release() {
            released.countDown();
        }
    }
}
