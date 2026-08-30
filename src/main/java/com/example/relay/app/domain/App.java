package com.example.relay.app.domain;

import com.example.relay.environment.domain.Environment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "apps")
@NoArgsConstructor
public class App {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @ManyToOne
    @JoinColumn(name = "environment_id", nullable = false, updatable = false)
    private Environment environment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public App(String name, Environment environment) {
        Instant now = Instant.now();

        this.id = UUID.randomUUID();
        this.name = name;
        this.environment = environment;
        this.createdAt = now;
    }

    // getters
    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof App)) {
            return false;
        }

        App that = (App) other;
        return id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
