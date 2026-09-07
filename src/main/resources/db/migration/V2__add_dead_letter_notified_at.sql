-- Control-plane column DeadLetterNotifier/ReconciliationSweeper use to guarantee at-most-one
-- dead-letter notification per attempt, closing the DEAD-path write-then-publish crash gap
-- documented in docs/superpowers/specs/2026-09-03-non-atomic-failure-handling-design.md.

ALTER TABLE attempts ADD COLUMN dead_letter_notified_at timestamp(6) with time zone;
