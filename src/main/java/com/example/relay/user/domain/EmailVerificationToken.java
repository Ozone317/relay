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
 * A single-use email-verification credential, structurally parallel to {@link PasswordResetToken} but deliberately not
 * sharing an entity/repository abstraction with it - matching this project's existing precedent of independently
 * defined sibling token tables. No {@code updatedAt}/dispatch-recovery columns - this feature has no automatic recovery
 * sweep (see the design spec Section 7); {@code resend} and re-registration are the only recovery paths.
 */
@Entity
@Table(name = "email_verification_tokens")
@NoArgsConstructor
public class EmailVerificationToken {

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

    public EmailVerificationToken(User user, String tokenHash, Instant expiresAt, Instant now) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = now;
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
}
