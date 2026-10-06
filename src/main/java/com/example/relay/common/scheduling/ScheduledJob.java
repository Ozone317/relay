package com.example.relay.common.scheduling;

/** Bounded metric identities for recurring production callbacks. */
public enum ScheduledJob {
    RETRY_PROMOTION("retry-promotion"),
    READY_DISPATCH("ready-dispatch"),
    DELIVERY_RECONCILIATION("delivery-reconciliation"),
    PASSWORD_RESET_EMAIL_RECOVERY("password-reset-email-recovery"),
    PASSWORD_RESET_TOKEN_CLEANUP("password-reset-token-cleanup");

    private final String metricTag;

    ScheduledJob(String metricTag) {
        this.metricTag = metricTag;
    }

    public String metricTag() {
        return metricTag;
    }
}
