-- Backfilled from created_at, not now() - created_at is the best available approximation of a pre-existing
-- row's true original request time, and being a plain column reference (not the volatile now()) lets Postgres
-- use the fast ADD COLUMN path (PG11+) instead of rewriting the whole table under an ACCESS EXCLUSIVE lock.
ALTER TABLE password_reset_tokens
    ADD COLUMN first_requested_at TIMESTAMPTZ;

UPDATE password_reset_tokens SET first_requested_at = created_at WHERE first_requested_at IS NULL;

ALTER TABLE password_reset_tokens
    ALTER COLUMN first_requested_at SET NOT NULL;
