package com.example.relay.email;

/**
 * Every email Relay can send, closed over this enum on purpose: {@link EmailService} is designed to
 * be reused by future, less-carefully-reviewed callers (starting with password-reset), and a raw
 * {@code String} template name would let any caller-controlled value become a classpath resource
 * path. Adding a new email type means adding a new constant here (and its template files under
 * {@code templates/email/}), never passing an arbitrary string through.
 */
public enum EmailTemplate {
    DEAD_LETTER_NOTIFICATION("dead-letter-notification");

    private final String resourceName;

    EmailTemplate(String resourceName) {
        this.resourceName = resourceName;
    }

    public String resourceName() {
        return resourceName;
    }
}
