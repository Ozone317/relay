package com.example.relay.common.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

class ScheduledCallbackAdmissionTest {

    @Test
    void admissionThatOwnsBoundaryFirstCanFinishAfterCloseWithoutBlockingClose() throws Exception {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        CountDownLatch admissionRecorded = new CountDownLatch(1);
        CountDownLatch releaseAdmissionBoundary = new CountDownLatch(1);
        CountDownLatch businessBodyEntered = new CountDownLatch(1);
        CountDownLatch releaseBusinessBody = new CountDownLatch(1);
        AtomicInteger businessWork = new AtomicInteger();
        AtomicBoolean admitted = new AtomicBoolean();
        ScheduledCallbackAdmission admission = new ScheduledCallbackAdmission(context,
                new ScheduledCallbackAdmission.BoundaryProbe() {
                    @Override
                    public void afterAdmissionRecorded() {
                        admissionRecorded.countDown();
                        awaitUninterruptibly(releaseAdmissionBoundary);
                    }

                    @Override
                    public void beforeCloseTransition() {}
                });
        Thread callbackThread = null;
        Thread closeThread = null;
        try {
            Thread callback = Thread.ofVirtual().start(() -> admitted.set(admission.runIfOpen(() -> {
                businessBodyEntered.countDown();
                awaitUninterruptibly(releaseBusinessBody);
                businessWork.incrementAndGet();
            })));
            callbackThread = callback;
            assertTrue(admissionRecorded.await(1, TimeUnit.SECONDS));

            CountDownLatch closeAttempted = new CountDownLatch(1);
            CountDownLatch closeFinished = new CountDownLatch(1);
            Thread closer = Thread.ofVirtual().start(() -> {
                closeAttempted.countDown();
                admission.onApplicationEvent(new ContextClosedEvent(context));
                closeFinished.countDown();
            });
            closeThread = closer;
            assertTrue(closeAttempted.await(1, TimeUnit.SECONDS));
            assertTrue(awaitCondition(() -> closer.getState() == Thread.State.BLOCKED, 1000),
                    "close should contend on the admission boundary held by the callback");

            releaseAdmissionBoundary.countDown();
            assertTrue(businessBodyEntered.await(1, TimeUnit.SECONDS));
            assertTrue(closeFinished.await(1, TimeUnit.SECONDS),
                    "close must not wait for an already-admitted business body");
            assertEquals(0, businessWork.get());

            releaseBusinessBody.countDown();
            callbackThread.join(1000);
            assertFalse(callbackThread.isAlive());
            assertTrue(admitted.get());
            assertEquals(1, businessWork.get());
        } finally {
            releaseAdmissionBoundary.countDown();
            releaseBusinessBody.countDown();
            if (callbackThread != null) callbackThread.join(1000);
            if (closeThread != null) closeThread.join(1000);
            context.close();
        }
    }

    @Test
    void closeThatOwnsBoundaryFirstDeniesContendingCallback() throws Exception {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        CountDownLatch closeOwnsBoundary = new CountDownLatch(1);
        CountDownLatch releaseCloseBoundary = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        CountDownLatch callbackAttempted = new CountDownLatch(1);
        AtomicInteger businessWork = new AtomicInteger();
        AtomicBoolean admitted = new AtomicBoolean(true);
        ScheduledCallbackAdmission admission = new ScheduledCallbackAdmission(context,
                new ScheduledCallbackAdmission.BoundaryProbe() {
                    @Override
                    public void afterAdmissionRecorded() {}

                    @Override
                    public void beforeCloseTransition() {
                        closeOwnsBoundary.countDown();
                        awaitUninterruptibly(releaseCloseBoundary);
                    }
                });
        Thread closeThread = null;
        Thread callbackThread = null;
        try {
            closeThread = Thread.ofVirtual().start(() -> {
                admission.onApplicationEvent(new ContextClosedEvent(context));
                closeFinished.countDown();
            });
            assertTrue(closeOwnsBoundary.await(1, TimeUnit.SECONDS));

            Thread callback = Thread.ofVirtual().start(() -> {
                callbackAttempted.countDown();
                admitted.set(admission.runIfOpen(businessWork::incrementAndGet));
            });
            callbackThread = callback;
            assertTrue(callbackAttempted.await(1, TimeUnit.SECONDS));
            assertTrue(awaitCondition(() -> callback.getState() == Thread.State.BLOCKED, 1000),
                    "callback should wait on the close transition's admission boundary");

            releaseCloseBoundary.countDown();
            assertTrue(closeFinished.await(1, TimeUnit.SECONDS));
            callbackThread.join(1000);
            assertFalse(callbackThread.isAlive());
            assertFalse(admitted.get());
            assertEquals(0, businessWork.get());
        } finally {
            releaseCloseBoundary.countDown();
            if (callbackThread != null) callbackThread.join(1000);
            if (closeThread != null) closeThread.join(1000);
            context.close();
        }
    }

    @Test
    void childCloseClosesOnlyChildAdmissionAndParentCloseClosesParentAdmission() {
        AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext();
        parent.registerBean(ScheduledCallbackAdmission.class);
        parent.refresh();
        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
        child.setParent(parent);
        child.registerBean(ScheduledCallbackAdmission.class);
        child.refresh();
        try {
            ScheduledCallbackAdmission parentAdmission = parent.getBean(ScheduledCallbackAdmission.class);
            ScheduledCallbackAdmission childAdmission = child.getBean(ScheduledCallbackAdmission.class);
            child.publishEvent(new ContextClosedEvent(child));

            AtomicInteger parentWork = new AtomicInteger();
            AtomicInteger childWork = new AtomicInteger();
            assertFalse(childAdmission.runIfOpen(childWork::incrementAndGet));
            assertTrue(parentAdmission.runIfOpen(parentWork::incrementAndGet));
            assertEquals(0, childWork.get());
            assertEquals(1, parentWork.get());

            parent.publishEvent(new ContextClosedEvent(parent));
            assertFalse(parentAdmission.runIfOpen(parentWork::incrementAndGet));
            assertEquals(1, parentWork.get());
        } finally {
            child.close();
            parent.close();
        }
    }

    @Test
    void approvedScheduledJobMetricIdentitiesAreClosedAndExact() {
        assertEquals(Set.of(
                        "retry-promotion",
                        "ready-dispatch",
                        "delivery-reconciliation",
                        "password-reset-email-recovery",
                        "password-reset-token-cleanup"),
                Set.of(java.util.Arrays.stream(ScheduledJob.values())
                        .map(ScheduledJob::metricTag)
                        .toArray(String[]::new)));
    }

    @Test
    void runnerRecordsAdmissionDenialWithoutEnteringBusinessOrCallbackMetrics() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        ScheduledCallbackAdmission admission = new ScheduledCallbackAdmission(context);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ScheduledJobMetrics metrics = new ScheduledJobMetrics(registry);
        ScheduledCallbackRunner runner = new ScheduledCallbackRunner(admission, metrics);
        AtomicInteger businessWork = new AtomicInteger();
        admission.onApplicationEvent(new ContextClosedEvent(context));

        runner.run(ScheduledJob.READY_DISPATCH, Duration.ofMinutes(1), businessWork::incrementAndGet);

        assertEquals(0, businessWork.get());
        assertEquals(1.0, registry.get("relay.scheduler.admission.denied")
                .tag("job", "ready-dispatch").counter().count());
        assertEquals(0, registry.getMeters().stream().filter(meter -> meter.getId().getName().equals(
                "relay.scheduler.callback.duration")).count());
        assertEquals(0, registry.getMeters().stream().filter(meter -> meter.getId().getName().equals(
                "relay.scheduler.invocation.lag")).count());
        context.close();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException expected) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static boolean awaitCondition(java.util.function.BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.yield();
        }
        return condition.getAsBoolean();
    }
}
