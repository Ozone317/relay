-- Every Attempt-creating path (AttemptService.createAttempts/createRetry/createReplay, and every
-- test fixture) now populates delivery_id - see docs/superpowers/specs/2026-09-09-delivery-entity-design.md
-- and V5's own comment for why this was deferred rather than set directly in V5.
ALTER TABLE attempts ALTER COLUMN delivery_id SET NOT NULL;
