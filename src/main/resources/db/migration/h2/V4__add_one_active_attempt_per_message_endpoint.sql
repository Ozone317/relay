-- See db/migration/postgresql/V4__add_one_active_attempt_per_message_endpoint.sql for the full
-- rationale. H2 2.x does not accept a WHERE clause on CREATE UNIQUE INDEX (verified empirically),
-- and does not accept the STORED keyword on a generated column either (also verified empirically)
-- - the column is computed correctly without it.
ALTER TABLE attempts ADD COLUMN active_endpoint_id uuid
    GENERATED ALWAYS AS (CASE WHEN status IN ('CREATED', 'IN_FLIGHT', 'SCHEDULED') THEN endpoint_id ELSE NULL END);

CREATE UNIQUE INDEX idx_attempts_one_active_per_message_endpoint
    ON attempts (message_id, active_endpoint_id);
