package com.example.relay.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.NoArgsConstructor;

/**
 * A single-use password reset credential. {@code updatedAt}/{@code resetEmailDispatchedAt} track a completely separate
 * concern from token validity (see {@link com.example.relay.user.infrastructure .PasswordResetTokenRepository}) -
 * whether the reset-link email for THIS row has ever been confirmed sent, recovered by
 * com.example.relay.user.recovery.PasswordResetEmailRecoverySweeper if not. {@code user} is a plain (default EAGER)
 * association, matching this project's existing convention for entities read outside an HTTP request's session (see
 * Attempt's relations, read by DeadLetterNotifier's @RabbitListener method with no explicit transaction) - the sweeper
 * here has the identical requirement.
 */
@Entity
@Table(name = "password_reset_tokens")
@NoArgsConstructor
public class PasswordResetToken {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, updatable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "reset_email_dispatched_at")
    private Instant resetEmailDispatchedAt;

    public PasswordResetToken(User user, String tokenHash, Instant expiresAt, Instant now) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getResetEmailDispatchedAt() {
        return resetEmailDispatchedAt;
    }
}
