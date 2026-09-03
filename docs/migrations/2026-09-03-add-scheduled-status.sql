-- docs/migrations/2026-09-03-add-scheduled-status.sql
--
-- Run this ONCE against any persistent Postgres database this application has ever connected to
-- with ddl-auto=update — including the local dev stack's `relay-dev_db-data` volume. It is NOT
-- needed for H2 (the default/test profile), which rebuilds its schema from scratch every run.
--
-- There is no migration tool (Flyway/Liquibase) in this project, so this script is applied by hand:
--   docker compose -f docker-compose.dev.yml exec db psql -U relay -d relay -f /path/to/this/file
-- or paste its contents into `docker compose -f docker-compose.dev.yml exec db psql -U relay -d relay`.

ALTER TABLE attempts DROP CONSTRAINT IF EXISTS attempts_status_check;

ALTER TABLE attempts ADD CONSTRAINT attempts_status_check
    CHECK (status IN ('CREATED', 'IN_FLIGHT', 'SCHEDULED', 'SUCCEEDED', 'FAILED_RETRYING', 'DEAD'));
