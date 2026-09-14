-- Backfilled to TRUE for every pre-existing row: Relay has never been publicly deployed (only run
-- locally/in Docker), so this is purely about not locking the developer out of their own local
-- accounts on next boot, not a grandfathering-real-users concern. New rows get FALSE set by the
-- application (User's constructor), never by a column default - see spec Section 3.
ALTER TABLE users
    ADD COLUMN email_verified BOOLEAN;

UPDATE users SET email_verified = TRUE WHERE email_verified IS NULL;

ALTER TABLE users
    ALTER COLUMN email_verified SET NOT NULL;

CREATE TABLE email_verification_tokens (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES users(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_email_verification_tokens_user_id ON email_verification_tokens(user_id);
