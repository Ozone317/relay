CREATE TABLE password_reset_tokens (
    id                        UUID PRIMARY KEY,
    user_id                   UUID NOT NULL REFERENCES users(id),
    token_hash                VARCHAR(64) NOT NULL UNIQUE,
    expires_at                TIMESTAMPTZ NOT NULL,
    used_at                   TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL,
    updated_at                TIMESTAMPTZ NOT NULL,
    reset_email_dispatched_at TIMESTAMPTZ
);

CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens(user_id);

CREATE INDEX idx_password_reset_tokens_dispatch_recovery
    ON password_reset_tokens(expires_at, updated_at)
    WHERE reset_email_dispatched_at IS NULL AND used_at IS NULL;
