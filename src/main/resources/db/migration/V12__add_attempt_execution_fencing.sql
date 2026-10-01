ALTER TABLE attempts
    ADD COLUMN execution_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN execution_claimed_at TIMESTAMPTZ;

UPDATE attempts SET execution_claimed_at = updated_at WHERE status = 'IN_FLIGHT';

ALTER TABLE attempts
    ADD CONSTRAINT attempts_execution_generation_nonnegative CHECK (execution_generation >= 0),
    ADD CONSTRAINT attempts_execution_claim_consistency
        CHECK ((status = 'IN_FLIGHT') = (execution_claimed_at IS NOT NULL));

CREATE INDEX idx_attempts_stale_in_flight
    ON attempts (execution_claimed_at, id) WHERE status = 'IN_FLIGHT';
