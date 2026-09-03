package com.example.relay.attempt.domain;

import com.example.relay.app.domain.App;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.message.domain.Message;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "attempts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Attempt {

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

    @Column(name = "attempt_no", nullable = false, updatable = false)
    @Getter
    private int attemptNo;

    @Column(name = "status", nullable = false, updatable = true)
    @Enumerated(EnumType.STRING)
    @Getter
    @Setter
    private AttemptStatus status;

    @Column(name = "next_retry_at", nullable = true, updatable = true)
    @Getter
    @Setter
    private Instant nextRetryAt;

    @Column(name = "response_code", nullable = true, updatable = true)
    @Getter
    @Setter
    private Integer responseCode;

    @Column(name = "response_body", nullable = true, updatable = true, length = 10240)
    @Getter
    @Setter
    private String responseBody;

    @Column(name = "last_error", nullable = true, updatable = true, length = 10240)
    @Getter
    @Setter
    private String lastError;

    @Column(name = "latency_ms", nullable = true, updatable = true)
    @Getter
    @Setter
    private Long latencyMs;

    @Column(name = "dead_letter_notified_at", nullable = true, updatable = true)
    @Getter
    @Setter
    private Instant deadLetterNotifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    @Getter
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = true)
    @UpdateTimestamp
    @Getter
    private Instant updatedAt;

    public Attempt(App app, Message message, Endpoint endpoint, Integer attemptNo) {
        this.id = UUID.randomUUID();
        this.app = app;
        this.message = message;
        this.endpoint = endpoint;
        this.attemptNo = attemptNo;
        this.status = AttemptStatus.CREATED;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof Attempt)) {
            return false;
        }

        Attempt that = (Attempt) other;
        return this.id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
