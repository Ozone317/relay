package com.example.relay.subscription.domain;

import com.example.relay.app.domain.App;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.event.domain.Event;
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
@Table(name = "subscriptions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_subscription_event_endpoint", columnNames = {"event_id", "endpoint_id"})})
@NoArgsConstructor
public class Subscription {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @Getter
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "app_id", nullable = false, updatable = false)
    @Getter
    private App app;

    @ManyToOne
    @JoinColumn(name = "event_id", nullable = false, updatable = false)
    @Getter
    private Event event;

    @ManyToOne
    @JoinColumn(name = "endpoint_id", nullable = false, updatable = false)
    @Getter
    private Endpoint endpoint;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Getter
    private Instant createdAt;

    public Subscription(App app, Event event, Endpoint endpoint) {
        Instant now = Instant.now();

        this.id = UUID.randomUUID();
        this.app = app;
        this.event = event;
        this.endpoint = endpoint;
        this.createdAt = now;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof Subscription)) {
            return false;
        }

        Subscription that = (Subscription) other;
        return this.id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
