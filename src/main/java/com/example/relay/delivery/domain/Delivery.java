package com.example.relay.delivery.domain;

import com.example.relay.app.domain.App;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.message.domain.Message;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "deliveries")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Delivery {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @Getter
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "app_id", nullable = false, updatable = false)
    @Getter
    private App app;

    @ManyToOne
    @JoinColumn(name = "message_id", nullable = false, updatable = false)
    @Getter
    private Message message;

    @ManyToOne
    @JoinColumn(name = "endpoint_id", nullable = false, updatable = false)
    @Getter
    private Endpoint endpoint;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    @Getter
    private Instant createdAt;

    public Delivery(App app, Message message, Endpoint endpoint) {
        this.id = UUID.randomUUID();
        this.app = app;
        this.message = message;
        this.endpoint = endpoint;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof Delivery)) {
            return false;
        }

        Delivery that = (Delivery) other;
        return this.id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
