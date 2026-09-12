package com.example.relay.email;

/**
 * {@link #SENT}: the provider accepted this send request as new.
 *
 * <p>
 * {@link #DUPLICATE}: the provider recognized the supplied idempotency key as one it had already
 * seen and did not process the request again. This does NOT mean "the email definitely reached the
 * recipient" - it only means the provider is refusing to send a second time under the same key.
 * Whether that is an acceptable basis for "don't send again" depends on the caller's use case; for
 * {@code DeadLetterNotifier}, both outcomes are treated as sufficient to proceed (see
 * docs/superpowers/specs/2026-09-12-email-integration-design.md Section 6.1).
 */
public enum EmailSendResult {
    SENT,
    DUPLICATE
}
