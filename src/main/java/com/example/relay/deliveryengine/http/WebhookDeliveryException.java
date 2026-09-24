package com.example.relay.deliveryengine.http;

import java.util.Objects;

/** Stable, bounded classification for an expected webhook destination or exchange failure. */
public final class WebhookDeliveryException extends Exception {

    private static final int DIAGNOSTIC_LIMIT = 512;

    private final WebhookFailureCode failureCode;
    private final String boundedDiagnostic;

    public WebhookDeliveryException(WebhookFailureCode failureCode, String boundedDiagnostic) {
        this(failureCode, boundedDiagnostic, null);
    }

    public WebhookDeliveryException(WebhookFailureCode failureCode, String boundedDiagnostic, Throwable cause) {
        super(sanitize(boundedDiagnostic), cause);
        this.failureCode = Objects.requireNonNull(failureCode, "failureCode");
        this.boundedDiagnostic = sanitize(boundedDiagnostic);
    }

    public WebhookFailureCode failureCode() {
        return failureCode;
    }

    public String boundedDiagnostic() {
        return boundedDiagnostic;
    }

    private static String sanitize(String diagnostic) {
        if (diagnostic == null || diagnostic.isBlank()) {
            return "webhook delivery failed";
        }
        StringBuilder result = new StringBuilder(Math.min(diagnostic.length(), DIAGNOSTIC_LIMIT));
        for (int i = 0; i < diagnostic.length() && result.length() < DIAGNOSTIC_LIMIT; i++) {
            char character = diagnostic.charAt(i);
            result.append(Character.isISOControl(character) ? ' ' : character);
        }
        return result.toString();
    }
}
