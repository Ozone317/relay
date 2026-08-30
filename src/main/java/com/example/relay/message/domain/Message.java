package com.example.relay.message.domain;

import com.example.relay.app.domain.App;
import com.example.relay.event.domain.Event;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "messages")
@NoArgsConstructor
public class Message {

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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "body", nullable = false, updatable = false)
    @Getter
    private JsonNode body;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Getter
    private Instant createdAt;

    public Message(App app, Event event, JsonNode body) {
        Instant now = Instant.now();

        this.id = UUID.randomUUID();
        this.app = app;
        this.event = event;
        this.body = body;
        this.createdAt = now;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof Message)) {
            return false;
        }

        Message that = (Message) other;
        return this.id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
