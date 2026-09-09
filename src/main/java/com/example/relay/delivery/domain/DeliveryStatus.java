package com.example.relay.delivery.domain;

import com.example.relay.attempt.domain.AttemptStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * Read-only mapping onto the {@code delivery_status} Postgres view (see migration V5). One row per
 * Delivery, already joined to its highest-attempt_no Attempt. Never written to directly - a
 * Delivery's status is always derived, never stored (see
 * docs/superpowers/specs/2026-09-09-delivery-entity-design.md Section 4).
 */
@Entity
@Immutable
@Table(name = "delivery_status")
@NoArgsConstructor
public class DeliveryStatus {

    @Id
    @Column(name = "delivery_id")
    @Getter
    private UUID deliveryId;

    @Column(name = "app_id")
    @Getter
    private UUID appId;

    @Column(name = "endpoint_id")
    @Getter
    private UUID endpointId;

    @Column(name = "endpoint_name")
    @Getter
    private String endpointName;

    @Column(name = "event_name")
    @Getter
    private String eventName;

    @Column(name = "message_id")
    @Getter
    private UUID messageId;

    @Column(name = "delivery_created_at")
    @Getter
    private Instant deliveryCreatedAt;

    @Column(name = "latest_attempt_id")
    @Getter
    private UUID latestAttemptId;

    @Column(name = "attempt_no")
    @Getter
    private int attemptNo;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    @Getter
    private AttemptStatus status;

    @Column(name = "response_code")
    @Getter
    private Integer responseCode;

    @Column(name = "latency_ms")
    @Getter
    private Long latencyMs;

    @Column(name = "last_attempt_at")
    @Getter
    private Instant lastAttemptAt;

    @Column(name = "attempt_count")
    @Getter
    private long attemptCount;
}
