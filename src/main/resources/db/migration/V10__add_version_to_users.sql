-- Adds optimistic-locking support to `users`, as defense-in-depth against any future full-entity
-- save silently losing a concurrent update - see
-- docs/superpowers/specs/2026-09-15-user-activation-concurrency-design.md. Default 0 for existing
-- rows; every write from here on increments it (either via Hibernate's own @Version handling on a
-- plain save, or explicitly in the atomic UPDATE queries added in Task 2).
ALTER TABLE users
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
