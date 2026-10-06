package com.example.relay.common.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.deliveryengine.dispatcher.ReadyWorkDispatcher;
import com.example.relay.deliveryengine.reconciliation.ReconciliationSweeper;
import com.example.relay.deliveryengine.retry.RetryScheduler;
import com.example.relay.user.recovery.PasswordResetEmailRecoverySweeper;
import com.example.relay.user.recovery.PasswordResetTokenCleanupTask;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ProductionSchedulerTopologyTest {

    @Test
    void everyProductionScheduledCallbackHasItsApprovedFixedDelayRoute() throws Exception {
        Map<Callback, Route> expected = Map.of(new Callback(RetryScheduler.class, "scheduledReleaseDueRetries"),
                new Route("${relay.retry.scheduler-interval}", "deliveryProgressTaskScheduler"),
                new Callback(ReadyWorkDispatcher.class, "scheduledDispatch"),
                new Route("${relay.retry.dispatcher-interval}", "deliveryProgressTaskScheduler"),
                new Callback(ReconciliationSweeper.class, "scheduledSweep"),
                new Route("${relay.reconciliation.interval}", "deliveryReconciliationTaskScheduler"),
                new Callback(PasswordResetEmailRecoverySweeper.class, "sweep"),
                new Route("${relay.password-reset.email-recovery.interval}", "passwordResetMaintenanceTaskScheduler"),
                new Callback(PasswordResetTokenCleanupTask.class, "cleanup"),
                new Route("${relay.password-reset.cleanup.interval}", "passwordResetMaintenanceTaskScheduler"));

        assertEquals(5, expected.size());
        StringBuilder mismatches = new StringBuilder();
        for (Map.Entry<Callback, Route> entry : expected.entrySet()) {
            Method method = entry.getKey().type().getMethod(entry.getKey().method());
            Scheduled annotation = method.getAnnotation(Scheduled.class);
            assertNotNull(annotation, () -> "missing @Scheduled on " + entry.getKey());
            if (!entry.getValue().fixedDelay().equals(annotation.fixedDelayString())
                    || !entry.getValue().scheduler().equals(annotation.scheduler())) {
                mismatches.append(entry.getKey()).append(" expected ").append(entry.getValue())
                        .append(" but got fixedDelay=").append(annotation.fixedDelayString()).append(", scheduler=")
                        .append(annotation.scheduler()).append(';');
            }
            assertTrue(annotation.initialDelay() < 0, () -> "explicit initial delay on " + entry.getKey());
            assertTrue(annotation.initialDelayString().isBlank(),
                    () -> "explicit initial delay string on " + entry.getKey());
            assertTrue(annotation.fixedDelay() < 0, () -> "numeric fixed delay on " + entry.getKey());
            assertFalse(annotation.fixedDelayString().isBlank(), () -> "blank fixed delay on " + entry.getKey());
            assertTrue(
                    annotation.fixedRate() < 0 && annotation.fixedRateString().isBlank() && annotation.cron().isBlank(),
                    () -> "non-fixed-delay trigger on " + entry.getKey());
            assertFalse(annotation.scheduler().isBlank(), () -> "blank scheduler on " + entry.getKey());
        }
        assertTrue(mismatches.isEmpty(), mismatches::toString);
    }

    @Test
    void unqualifiedScheduledCallbackFailsContextRefreshThroughTheGuard() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(SchedulingEnabledConfiguration.class);
            context.registerBean("taskScheduler", UnclassifiedTaskScheduler.class);
            context.registerBean("otherScheduler", ThreadPoolTaskScheduler.class, ThreadPoolTaskScheduler::new);
            context.registerBean("callback", UnqualifiedCallback.class);

            RuntimeException failure = assertThrows(RuntimeException.class, context::refresh);

            IllegalStateException classified = causeOf(failure, IllegalStateException.class);
            assertNotNull(classified);
            assertTrue(classified.getMessage()
                    .contains("Every production @Scheduled method must declare an approved scheduler"));
        }
    }

    @Test
    void approvedSchedulerQualifierRegistersExactlyOneTaskOnItsNamedScheduler() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(SchedulingEnabledConfiguration.class);
            context.registerBean("approvedScheduler", RecordingTaskScheduler.class, RecordingTaskScheduler::new);
            context.registerBean("otherScheduler", RecordingTaskScheduler.class, RecordingTaskScheduler::new);
            context.registerBean("callback", ApprovedCallback.class);
            context.refresh();

            assertEquals(1, context.getBean("approvedScheduler", RecordingTaskScheduler.class).registrations());
            assertEquals(0, context.getBean("otherScheduler", RecordingTaskScheduler.class).registrations());
        }
    }

    @Test
    void unknownSchedulerQualifierFailsRefreshWithTheRequestedBeanName() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(SchedulingEnabledConfiguration.class);
            context.registerBean("taskScheduler", UnclassifiedTaskScheduler.class);
            context.registerBean("otherScheduler", ThreadPoolTaskScheduler.class, ThreadPoolTaskScheduler::new);
            context.registerBean("callback", UnknownSchedulerCallback.class);

            RuntimeException failure = assertThrows(RuntimeException.class, context::refresh);

            NoSuchBeanDefinitionException missing = causeOf(failure, NoSuchBeanDefinitionException.class);
            assertNotNull(missing);
            assertTrue(missing.getMessage().contains("missingScheduler"));
        }
    }

    private static <T extends Throwable> T causeOf(Throwable failure, Class<T> causeType) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (causeType.isInstance(cause)) {
                return causeType.cast(cause);
            }
        }
        return null;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingEnabledConfiguration {
    }

    static class UnqualifiedCallback {
        @Scheduled(fixedDelayString = "3600000")
        public void run() {}
    }

    static class ApprovedCallback {
        @Scheduled(fixedDelayString = "3600000", scheduler = "approvedScheduler")
        public void run() {}
    }

    static class UnknownSchedulerCallback {
        @Scheduled(fixedDelayString = "3600000", scheduler = "missingScheduler")
        public void run() {}
    }

    static class RecordingTaskScheduler extends ThreadPoolTaskScheduler {
        private final AtomicInteger registrations = new AtomicInteger();

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            registrations.incrementAndGet();
            return super.scheduleWithFixedDelay(task, delay);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, java.time.Instant startTime, Duration delay) {
            registrations.incrementAndGet();
            return super.scheduleWithFixedDelay(task, startTime, delay);
        }

        int registrations() {
            return registrations.get();
        }
    }

    private record Callback(Class<?> type, String method) {
    }

    private record Route(String fixedDelay, String scheduler) {
    }
}
