ALTER TABLE attempts ADD COLUMN ready_published_at TIMESTAMPTZ;
ALTER TABLE attempts ADD COLUMN ready_dispatch_claim_id UUID;
ALTER TABLE attempts ADD COLUMN ready_dispatch_claimed_at TIMESTAMPTZ;

CREATE INDEX idx_attempts_scheduled_due
    ON attempts (next_retry_at, id) WHERE status = 'SCHEDULED';

CREATE INDEX idx_attempts_unpublished_ready
    ON attempts (ready_dispatch_claimed_at, id)
    WHERE status = 'CREATED' AND ready_published_at IS NULL;
