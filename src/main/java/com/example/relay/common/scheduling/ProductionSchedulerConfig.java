package com.example.relay.common.scheduling;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class ProductionSchedulerConfig {

    @Bean(name = SchedulerNames.DELIVERY_PROGRESS)
    ThreadPoolTaskScheduler deliveryProgressTaskScheduler() {
        return scheduler(2, "relay-delivery-progress-");
    }

    @Bean(name = SchedulerNames.DELIVERY_RECONCILIATION)
    ThreadPoolTaskScheduler deliveryReconciliationTaskScheduler() {
        return scheduler(1, "relay-delivery-reconciliation-");
    }

    @Bean(name = SchedulerNames.PASSWORD_RESET_MAINTENANCE)
    ThreadPoolTaskScheduler passwordResetMaintenanceTaskScheduler() {
        return scheduler(2, "relay-password-reset-maintenance-");
    }

    @Bean(name = SchedulerNames.WEBHOOK_DEADLINE)
    ThreadPoolTaskScheduler webhookDeadlineTaskScheduler() {
        return scheduler(1, "relay-webhook-deadline-");
    }

    @Bean(name = SchedulerNames.UNCLASSIFIED_DEFAULT)
    UnclassifiedTaskScheduler taskScheduler() {
        return new UnclassifiedTaskScheduler();
    }

    private ThreadPoolTaskScheduler scheduler(int poolSize, String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
