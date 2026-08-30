package com.example.relay.event.domain;

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
import lombok.NoArgsConstructor;

@Entity
@Table(name = "events",
        uniqueConstraints = {@UniqueConstraint(name = "uk_event_app_name", columnNames = {"app_id", "name"})})
@NoArgsConstructor
public class Event {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    @ManyToOne
    @JoinColumn(name = "app_id", nullable = false, updatable = false)
    private App app;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Event(String name, App app) {
        Instant now = Instant.now();

        this.id = UUID.randomUUID();
        this.name = name;
        this.app = app;
        this.createdAt = now;
    }

    // getters
    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public App getApp() {
        return app;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof Event)) {
            return false;
        }

        Event that = (Event) other;
        return id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
