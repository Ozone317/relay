package com.example.relay.common.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ScheduledProgressIsolationTest {

    @Test
    void productionSchedulersHaveTheRequiredIsolatedLifecycle() throws Exception {
        try (AnnotationConfigApplicationContext context = productionContext()) {
            assertScheduler(context.getBean("deliveryProgressTaskScheduler", ThreadPoolTaskScheduler.class), 2,
                    "relay-delivery-progress-");
            assertScheduler(context.getBean("deliveryReconciliationTaskScheduler", ThreadPoolTaskScheduler.class), 1,
                    "relay-delivery-reconciliation-");
            assertScheduler(context.getBean("passwordResetMaintenanceTaskScheduler", ThreadPoolTaskScheduler.class), 2,
                    "relay-password-reset-maintenance-");
            assertScheduler(context.getBean("webhookDeadlineTaskScheduler", ThreadPoolTaskScheduler.class), 1,
                    "relay-webhook-deadline-");
        }
    }

    @Test
    void passwordResetWorkCannotOccupyDeliveryProgressCapacity() throws Exception {
        try (AnnotationConfigApplicationContext context = productionContext()) {
            ThreadPoolTaskScheduler password = scheduler(context, "passwordResetMaintenanceTaskScheduler");
            ThreadPoolTaskScheduler delivery = scheduler(context, "deliveryProgressTaskScheduler");
            CountDownLatch blockerEntered = new CountDownLatch(1);
            CountDownLatch releaseBlocker = new CountDownLatch(1);
            CountDownLatch deliveryInvoked = new CountDownLatch(1);

            password.execute(blockUntilReleased(blockerEntered, releaseBlocker));
            assertTrue(blockerEntered.await(2, TimeUnit.SECONDS));
            delivery.execute(deliveryInvoked::countDown);

            assertTrue(deliveryInvoked.await(2, TimeUnit.SECONDS));
            releaseBlocker.countDown();
        }
    }

    @Test
    void deliveryWorkCannotOccupyReconciliationOrPasswordResetCapacity() throws Exception {
        try (AnnotationConfigApplicationContext context = productionContext()) {
            ThreadPoolTaskScheduler delivery = scheduler(context, "deliveryProgressTaskScheduler");
            ThreadPoolTaskScheduler reconciliation = scheduler(context, "deliveryReconciliationTaskScheduler");
            ThreadPoolTaskScheduler password = scheduler(context, "passwordResetMaintenanceTaskScheduler");
            CountDownLatch blockerEntered = new CountDownLatch(2);
            CountDownLatch releaseBlockers = new CountDownLatch(1);
            CountDownLatch otherDomainsInvoked = new CountDownLatch(2);

            delivery.execute(blockUntilReleased(blockerEntered, releaseBlockers));
            delivery.execute(blockUntilReleased(blockerEntered, releaseBlockers));
            assertTrue(blockerEntered.await(2, TimeUnit.SECONDS));
            reconciliation.execute(otherDomainsInvoked::countDown);
            password.execute(otherDomainsInvoked::countDown);

            assertTrue(otherDomainsInvoked.await(2, TimeUnit.SECONDS));
            releaseBlockers.countDown();
        }
    }

    @Test
    void oneBlockedMemberLeavesTheSecondDeliverySlotAvailable() throws Exception {
        try (AnnotationConfigApplicationContext context = productionContext()) {
            ThreadPoolTaskScheduler delivery = scheduler(context, "deliveryProgressTaskScheduler");
            CountDownLatch blockerEntered = new CountDownLatch(1);
            CountDownLatch releaseBlocker = new CountDownLatch(1);
            CountDownLatch otherSlotInvoked = new CountDownLatch(1);

            delivery.execute(blockUntilReleased(blockerEntered, releaseBlocker));
            assertTrue(blockerEntered.await(2, TimeUnit.SECONDS));
            delivery.execute(otherSlotInvoked::countDown);

            assertTrue(otherSlotInvoked.await(2, TimeUnit.SECONDS));
            releaseBlocker.countDown();
        }
    }

    @Test
    void bothDeliverySlotsQueueAThirdTaskUntilAWorkerIsReleased() throws Exception {
        assertTwoSlotQueueing("deliveryProgressTaskScheduler");
    }

    @Test
    void bothPasswordMaintenanceSlotsQueueAThirdTaskUntilAWorkerIsReleased() throws Exception {
        assertTwoSlotQueueing("passwordResetMaintenanceTaskScheduler");
    }

    @Test
    void throwingCallbackDoesNotPermanentlyConsumeReconciliationCapacity() throws Exception {
        try (AnnotationConfigApplicationContext context = productionContext()) {
            ThreadPoolTaskScheduler reconciliation = scheduler(context, "deliveryReconciliationTaskScheduler");
            CountDownLatch throwingCallbackInvoked = new CountDownLatch(1);
            CountDownLatch nextCallbackInvoked = new CountDownLatch(1);

            reconciliation.scheduleWithFixedDelay(() -> {
                throwingCallbackInvoked.countDown();
                throw new IllegalStateException("expected adversarial callback failure");
            }, java.time.Duration.ofMillis(1));
            assertTrue(throwingCallbackInvoked.await(2, TimeUnit.SECONDS));
            reconciliation.execute(nextCallbackInvoked::countDown);

            assertTrue(nextCallbackInvoked.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void closingWithBothDeliverySlotsOccupiedCompletesAfterRelease() throws Exception {
        AnnotationConfigApplicationContext context = productionContext();
        ThreadPoolTaskScheduler delivery = scheduler(context, "deliveryProgressTaskScheduler");
        CountDownLatch blockersEntered = new CountDownLatch(2);
        CountDownLatch releaseBlockers = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch closeCompleted = new CountDownLatch(1);

        delivery.execute(blockUntilReleased(blockersEntered, releaseBlockers));
        delivery.execute(blockUntilReleased(blockersEntered, releaseBlockers));
        assertTrue(blockersEntered.await(2, TimeUnit.SECONDS));
        Thread closer = new Thread(() -> {
            closeStarted.countDown();
            context.close();
            closeCompleted.countDown();
        });
        closer.start();
        try {
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
            releaseBlockers.countDown();
            assertTrue(closeCompleted.await(2, TimeUnit.SECONDS));
        } finally {
            releaseBlockers.countDown();
            closer.join(2_000);
            context.close();
        }
    }

    private static void assertTwoSlotQueueing(String beanName) throws Exception {
        try (AnnotationConfigApplicationContext context = productionContext()) {
            ThreadPoolTaskScheduler scheduler = scheduler(context, beanName);
            CountDownLatch blockersEntered = new CountDownLatch(2);
            CountDownLatch releaseBlockers = new CountDownLatch(1);
            CountDownLatch thirdTaskInvoked = new CountDownLatch(1);
            AtomicBoolean thirdTaskRan = new AtomicBoolean();

            scheduler.execute(blockUntilReleased(blockersEntered, releaseBlockers));
            scheduler.execute(blockUntilReleased(blockersEntered, releaseBlockers));
            assertTrue(blockersEntered.await(2, TimeUnit.SECONDS));
            scheduler.execute(() -> {
                thirdTaskRan.set(true);
                thirdTaskInvoked.countDown();
            });

            assertEquals(1, scheduler.getScheduledThreadPoolExecutor().getQueue().size());
            assertFalse(thirdTaskRan.get());
            releaseBlockers.countDown();
            assertTrue(thirdTaskInvoked.await(2, TimeUnit.SECONDS));
            assertTrue(thirdTaskRan.get());
        }
    }

    private static void assertScheduler(ThreadPoolTaskScheduler scheduler, int poolSize, String prefix)
            throws Exception {
        assertEquals(poolSize, scheduler.getScheduledThreadPoolExecutor().getCorePoolSize());
        assertTrue(scheduler.getThreadNamePrefix().startsWith(prefix));
        assertTrue(scheduler.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy());
        assertFalse(scheduler.getScheduledThreadPoolExecutor().getExecuteExistingDelayedTasksAfterShutdownPolicy());
        assertFalse(scheduler.getScheduledThreadPoolExecutor().getContinueExistingPeriodicTasksAfterShutdownPolicy());
        Field terminationTimeout = scheduler.getClass().getSuperclass().getDeclaredField("awaitTerminationMillis");
        terminationTimeout.setAccessible(true);
        assertEquals(5_000L, terminationTimeout.getLong(scheduler));

        CountDownLatch threadObserved = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        scheduler.execute(() -> {
            worker.set(Thread.currentThread());
            threadObserved.countDown();
        });
        assertTrue(threadObserved.await(2, TimeUnit.SECONDS));
        assertTrue(worker.get().isDaemon());
        assertTrue(worker.get().getName().startsWith(prefix));
    }

    private static Runnable blockUntilReleased(CountDownLatch entered, CountDownLatch release) {
        return () -> {
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("blocker was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        };
    }

    private static ThreadPoolTaskScheduler scheduler(AnnotationConfigApplicationContext context, String name) {
        return context.getBean(name, ThreadPoolTaskScheduler.class);
    }

    private static AnnotationConfigApplicationContext productionContext() throws Exception {
        Class<?> configuration = Class.forName("com.example.relay.common.scheduling.ProductionSchedulerConfig");
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        context.register(configuration);
        context.refresh();
        return context;
    }
}
