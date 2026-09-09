-- Introduces a Delivery entity above Attempt: one row per (message, endpoint) pair, representing
-- "the act of delivering this message to this endpoint". Write-once, never updated - see
-- docs/superpowers/specs/2026-09-09-delivery-entity-design.md Section 4 for why a stateful,
-- independently-written status column was deliberately rejected.

CREATE TABLE deliveries (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id      UUID NOT NULL REFERENCES apps(id),
    message_id  UUID NOT NULL REFERENCES messages(id),
    endpoint_id UUID NOT NULL REFERENCES endpoints(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_deliveries_message_endpoint ON deliveries(message_id, endpoint_id);
CREATE INDEX idx_deliveries_app ON deliveries(app_id);

-- Nullable at first so existing rows can be backfilled before the NOT NULL is enforced below.
ALTER TABLE attempts ADD COLUMN delivery_id UUID REFERENCES deliveries(id);

-- Backfill: one Delivery per distinct (app, message, endpoint) pair already present in attempts,
-- using that pair's earliest attempt as the delivery's own created_at. Safe on this project's
-- dev-only Postgres volume - there is no production data yet (handoff Section 19).
INSERT INTO deliveries (id, app_id, message_id, endpoint_id, created_at)
SELECT gen_random_uuid(), a.app_id, a.message_id, a.endpoint_id, MIN(a.created_at)
FROM attempts a
GROUP BY a.app_id, a.message_id, a.endpoint_id;

UPDATE attempts a
SET delivery_id = d.id
FROM deliveries d
WHERE a.message_id = d.message_id AND a.endpoint_id = d.endpoint_id;

-- delivery_id is deliberately left NULLABLE here, not NOT NULL. The Attempt JPA entity doesn't
-- gain a `delivery` field until Task 4 - if this were NOT NULL now, every existing test between
-- this task and Task 4 that persists an Attempt (i.e. most of the suite) would violate the
-- constraint, since Hibernate has no mapping to populate the column. Task 4 adds a follow-up
-- migration (V6) enforcing NOT NULL once every Attempt-creating code path actually populates it.

-- Backs the delivery_status view's DISTINCT ON below: "latest attempt for this delivery" is
-- ordered by attempt_no, not created_at - see spec Section 5.1 for why (this project's own
-- clock-skew lesson, handoff Section 10).
CREATE INDEX idx_attempts_delivery_attempt_no ON attempts(delivery_id, attempt_no DESC);

-- One row per delivery, already joined to its latest attempt and denormalized display names.
-- attempt_count is a window function evaluated per input row BEFORE DISTINCT ON collapses the
-- group, so it correctly reflects the full attempt count for that delivery at zero extra query
-- cost. Filtering/sorting on this view uses a Specification-based dynamic-predicate pattern rather
-- than a hand-rolled native query with IS NULL OR - see DeliveryStatusSpecifications' javadoc for
-- why (a real PostgreSQL bug this project hit before: untyped bind parameters and PREPARE-time
-- type inference, SQLState 42P18).
CREATE VIEW delivery_status AS
SELECT DISTINCT ON (a.delivery_id)
    a.delivery_id                              AS delivery_id,
    d.app_id                                   AS app_id,
    d.endpoint_id                              AS endpoint_id,
    ep.name                                    AS endpoint_name,
    ev.name                                    AS event_name,
    d.message_id                               AS message_id,
    d.created_at                               AS delivery_created_at,
    a.id                                       AS latest_attempt_id,
    a.attempt_no                               AS attempt_no,
    a.status                                   AS status,
    a.response_code                            AS response_code,
    a.latency_ms                               AS latency_ms,
    a.created_at                               AS last_attempt_at,
    COUNT(*) OVER (PARTITION BY a.delivery_id) AS attempt_count
FROM attempts a
JOIN deliveries d ON d.id = a.delivery_id
JOIN endpoints ep ON ep.id = d.endpoint_id
JOIN messages m ON m.id = d.message_id
JOIN events ev ON ev.id = m.event_id
ORDER BY a.delivery_id, a.attempt_no DESC;
