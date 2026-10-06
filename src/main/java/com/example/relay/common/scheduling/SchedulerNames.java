package com.example.relay.common.scheduling;

public final class SchedulerNames {

    public static final String DELIVERY_PROGRESS = "deliveryProgressTaskScheduler";
    public static final String DELIVERY_RECONCILIATION = "deliveryReconciliationTaskScheduler";
    public static final String PASSWORD_RESET_MAINTENANCE = "passwordResetMaintenanceTaskScheduler";
    public static final String WEBHOOK_DEADLINE = "webhookDeadlineTaskScheduler";
    public static final String UNCLASSIFIED_DEFAULT = "taskScheduler";

    private SchedulerNames() {}
}
