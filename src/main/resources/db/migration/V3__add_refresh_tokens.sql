-- Server-side session state backing the refresh-token flow and a real logout endpoint.
-- One row per login. token_hash is SHA-256, deliberately NOT bcrypt: the input is 256 bits of
-- SecureRandom, so there is no dictionary to defend against and bcrypt's cost would buy nothing
-- while adding ~100ms to every refresh. Do not "fix" this for symmetry with users.password.
-- See docs/superpowers/specs/2026-09-05-session-auth-and-logout-design.md

CREATE TABLE refresh_tokens (
    id         uuid                        NOT NULL,
    user_id    uuid                        NOT NULL,
    token_hash varchar(64)                 NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    revoked_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users
);

CREATE UNIQUE INDEX idx_refresh_tokens_hash ON refresh_tokens (token_hash);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
