package com.example.relay.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@NoArgsConstructor
public class User {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password", nullable = false)
    private String passwordHash;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public User(String email, String passwordHash) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.passwordHash = passwordHash;
        this.emailVerified = false;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * Test-only in-memory mutation; production activation goes through
     * {@code UserRepository.activateIfPending}/{@code setPasswordOnly}'s atomic UPDATE queries, never a
     * load-mutate-save on this entity. Do not wire a production caller back up to this method - see
     * docs/superpowers/specs/2026-09-15-user-activation-concurrency-design.md for why that pattern was removed.
     */
    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    /**
     * Test-only in-memory mutation; production activation goes through
     * {@code UserRepository.activateIfPending}'s atomic UPDATE, never a load-mutate-save on this entity. Do not wire
     * a production caller back up to this method - see
     * docs/superpowers/specs/2026-09-15-user-activation-concurrency-design.md for why that pattern was removed.
     */
    public void markEmailVerified() {
        this.emailVerified = true;
    }

    public Long getVersion() {
        return version;
    }
}
