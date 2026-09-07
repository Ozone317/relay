-- Enforces an invariant the application already relies on: at most one Attempt row per
-- (message_id, endpoint_id) pair is ever in one of the "active" statuses (CREATED, IN_FLIGHT,
-- SCHEDULED) at a time. Backs the manual-replay concurrency guard - see
-- docs/superpowers/specs/2026-09-07-attempt-replay-design.md Section 4.3.
--
-- Postgres/H2 both support a plain WHERE clause on CREATE UNIQUE INDEX in principle, but H2 2.x
-- does not accept it in practice (verified empirically - syntax error at the WHERE keyword). This
-- generated-column + plain UNIQUE index form works identically on both: active_endpoint_id is
-- endpoint_id when the row is active, NULL otherwise, and a UNIQUE index treats every NULL as
-- distinct from every other NULL (ANSI-standard, true on both engines), so only non-NULL
-- (message_id, active_endpoint_id) pairs are actually constrained.
ALTER TABLE attempts ADD COLUMN active_endpoint_id uuid
    GENERATED ALWAYS AS (CASE WHEN status IN ('CREATED', 'IN_FLIGHT', 'SCHEDULED') THEN endpoint_id ELSE NULL END) STORED;

CREATE UNIQUE INDEX idx_attempts_one_active_per_message_endpoint
    ON attempts (message_id, active_endpoint_id);
