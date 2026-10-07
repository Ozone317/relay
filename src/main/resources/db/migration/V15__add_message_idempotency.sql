CREATE TABLE message_idempotency (
    user_id             UUID         NOT NULL REFERENCES users(id),
    app_id              UUID         NOT NULL REFERENCES apps(id),
    idempotency_key     VARCHAR(255) COLLATE "C" NOT NULL,
    fingerprint_version SMALLINT     NOT NULL,
    message_id          UUID         NOT NULL,
    accepted_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_message_idempotency
        PRIMARY KEY (user_id, app_id, idempotency_key),
    CONSTRAINT uk_message_idempotency_message
        UNIQUE (message_id),
    CONSTRAINT ck_message_idempotency_key_length
        CHECK (octet_length(idempotency_key) BETWEEN 1 AND 255),
    CONSTRAINT ck_message_idempotency_fingerprint_version
        CHECK (fingerprint_version = 1),
    CONSTRAINT fk_message_idempotency_message
        FOREIGN KEY (message_id) REFERENCES messages(id)
        DEFERRABLE INITIALLY DEFERRED
);
