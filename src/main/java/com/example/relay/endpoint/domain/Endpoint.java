package com.example.relay.endpoint.domain;

import com.example.relay.app.domain.App;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "endpoints",
        uniqueConstraints = {@UniqueConstraint(name = "uk_endpoint_app_name", columnNames = {"app_id", "name"})})
@NoArgsConstructor
public class Endpoint {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @Getter
    private UUID id;

    @Column(name = "name", nullable = false, updatable = true)
    @Getter
    private String name;

    @Column(name = "url", nullable = false, updatable = true)
    @Getter
    private String url;

    @Column(name = "signing_secret", nullable = false, updatable = false)
    @Getter
    private String signingSecret;

    @Column(name = "is_active", nullable = false, updatable = true)
    @Getter
    private boolean active;

    @ManyToOne
    @JoinColumn(name = "app_id", nullable = false, updatable = false)
    @Getter
    private App app;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Getter
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = true)
    @Getter
    private Instant updatedAt;

    public Endpoint(String name, String url, String signingSecret, App app) {
        Instant now = Instant.now();

        this.id = UUID.randomUUID();
        this.name = name;
        this.url = url;
        this.signingSecret = signingSecret;
        this.active = true;
        this.app = app;
        this.createdAt = now;
        this.updatedAt = now;
    }

    // setters
    public String setName(String name) {
        this.name = name;
        this.updatedAt = Instant.now();
        return this.name;
    }

    public boolean setActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
        return this.active;
    }

    public String setUrl(String url) {
        this.url = url;
        this.updatedAt = Instant.now();
        return this.url;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Endpoint)) {
            return false;
        }

        Endpoint that = (Endpoint) other;
        return this.id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
