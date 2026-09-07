-- Baseline schema, matching the tables Hibernate's ddl-auto=update had built up to this point
-- (verified by exporting the actual DDL Hibernate generates from the current entity classes).
-- attempts.status already includes SCHEDULED, folding in the constraint change that used to be
-- applied by hand via docs/migrations/2026-09-03-add-scheduled-status.sql.

CREATE TABLE users (
    id       uuid         NOT NULL,
    email    varchar(255) NOT NULL UNIQUE,
    password varchar(255) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE environments (
    id          uuid                     NOT NULL,
    user_id     uuid                     NOT NULL,
    name        varchar(255)             NOT NULL,
    description varchar(255),
    created_at  timestamp(6) with time zone NOT NULL,
    updated_at  timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_environments_user FOREIGN KEY (user_id) REFERENCES users
);

CREATE TABLE apps (
    id             uuid                     NOT NULL,
    name           varchar(255)             NOT NULL,
    environment_id uuid                     NOT NULL,
    created_at     timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_apps_environment FOREIGN KEY (environment_id) REFERENCES environments
);

CREATE TABLE endpoints (
    id              uuid                     NOT NULL,
    name            varchar(255)             NOT NULL,
    url             varchar(255)             NOT NULL,
    signing_secret  varchar(255)             NOT NULL,
    is_active       boolean                  NOT NULL,
    app_id          uuid                     NOT NULL,
    created_at      timestamp(6) with time zone NOT NULL,
    updated_at      timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_endpoint_app_name UNIQUE (app_id, name),
    CONSTRAINT fk_endpoints_app FOREIGN KEY (app_id) REFERENCES apps
);

CREATE TABLE events (
    id         uuid                     NOT NULL,
    name       varchar(255)             NOT NULL,
    app_id     uuid                     NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_event_app_name UNIQUE (app_id, name),
    CONSTRAINT fk_events_app FOREIGN KEY (app_id) REFERENCES apps
);

CREATE TABLE messages (
    id         uuid                     NOT NULL,
    app_id     uuid                     NOT NULL,
    event_id   uuid                     NOT NULL,
    body       jsonb                    NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_messages_app FOREIGN KEY (app_id) REFERENCES apps,
    CONSTRAINT fk_messages_event FOREIGN KEY (event_id) REFERENCES events
);

CREATE TABLE subscriptions (
    id          uuid                     NOT NULL,
    app_id      uuid                     NOT NULL,
    event_id    uuid                     NOT NULL,
    endpoint_id uuid                     NOT NULL,
    created_at  timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_subscription_event_endpoint UNIQUE (event_id, endpoint_id),
    CONSTRAINT fk_subscriptions_app FOREIGN KEY (app_id) REFERENCES apps,
    CONSTRAINT fk_subscriptions_event FOREIGN KEY (event_id) REFERENCES events,
    CONSTRAINT fk_subscriptions_endpoint FOREIGN KEY (endpoint_id) REFERENCES endpoints
);

CREATE TABLE attempts (
    id             uuid                     NOT NULL,
    app_id         uuid                     NOT NULL,
    message_id     uuid                     NOT NULL,
    endpoint_id    uuid                     NOT NULL,
    attempt_no     integer                  NOT NULL,
    status         varchar(255)             NOT NULL,
    next_retry_at  timestamp(6) with time zone,
    response_code  integer,
    response_body  varchar(10240),
    last_error     varchar(10240),
    latency_ms     bigint,
    created_at     timestamp(6) with time zone NOT NULL,
    updated_at     timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT attempts_status_check CHECK (status IN ('CREATED', 'IN_FLIGHT', 'SCHEDULED', 'SUCCEEDED', 'FAILED_RETRYING', 'DEAD')),
    CONSTRAINT fk_attempts_app FOREIGN KEY (app_id) REFERENCES apps,
    CONSTRAINT fk_attempts_message FOREIGN KEY (message_id) REFERENCES messages,
    CONSTRAINT fk_attempts_endpoint FOREIGN KEY (endpoint_id) REFERENCES endpoints
);
