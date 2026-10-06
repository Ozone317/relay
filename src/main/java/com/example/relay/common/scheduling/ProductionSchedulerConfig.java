package com.example.relay.common.scheduling;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.util.ErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class ProductionSchedulerConfig {

    @Bean(name = SchedulerNames.DELIVERY_PROGRESS)
    ThreadPoolTaskScheduler deliveryProgressTaskScheduler(SchedulerErrorHandlerFactory errorHandlerFactory) {
        return scheduler(2, "relay-delivery-progress-",
                errorHandlerFactory.forScheduler(SchedulerNames.DELIVERY_PROGRESS));
    }

    @Bean(name = SchedulerNames.DELIVERY_RECONCILIATION)
    ThreadPoolTaskScheduler deliveryReconciliationTaskScheduler(SchedulerErrorHandlerFactory errorHandlerFactory) {
        return scheduler(1, "relay-delivery-reconciliation-",
                errorHandlerFactory.forScheduler(SchedulerNames.DELIVERY_RECONCILIATION));
    }

    @Bean(name = SchedulerNames.PASSWORD_RESET_MAINTENANCE)
    ThreadPoolTaskScheduler passwordResetMaintenanceTaskScheduler(SchedulerErrorHandlerFactory errorHandlerFactory) {
        return scheduler(2, "relay-password-reset-maintenance-",
                errorHandlerFactory.forScheduler(SchedulerNames.PASSWORD_RESET_MAINTENANCE));
    }

    @Bean(name = SchedulerNames.WEBHOOK_DEADLINE)
    ThreadPoolTaskScheduler webhookDeadlineTaskScheduler(SchedulerErrorHandlerFactory errorHandlerFactory) {
        ThreadPoolTaskScheduler scheduler = scheduler(1, "relay-webhook-deadline-",
                errorHandlerFactory.forScheduler(SchedulerNames.WEBHOOK_DEADLINE));
        scheduler.setAcceptTasksAfterContextClose(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    @Bean(name = SchedulerNames.UNCLASSIFIED_DEFAULT)
    UnclassifiedTaskScheduler taskScheduler() {
        return new UnclassifiedTaskScheduler();
    }

    @Bean
    ScheduledJobMetrics scheduledJobMetrics(MeterRegistry meterRegistry) {
        return new ScheduledJobMetrics(meterRegistry);
    }

    @Bean
    SchedulerErrorHandlerFactory schedulerErrorHandlerFactory(MeterRegistry meterRegistry) {
        return new SchedulerErrorHandlerFactory(meterRegistry);
    }

    private ThreadPoolTaskScheduler scheduler(int poolSize, String threadNamePrefix,
            ErrorHandler errorHandler) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.setErrorHandler(errorHandler);
        return scheduler;
    }
}
