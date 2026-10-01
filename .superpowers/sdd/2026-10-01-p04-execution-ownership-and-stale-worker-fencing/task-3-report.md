# Task 3 report: atomic retry creation with stale-worker fencing

## Changes

- `src/main/java/com/example/relay/attempt/application/AttemptService.java`
  - `markFailedAndCreateRetry` now performs the fenced parent update directly in the existing transaction.
  - A row count of zero maps to `OWNERSHIP_LOST`; exactly one permits retry creation and returns `APPLIED`; other counts throw `IllegalStateException`.
  - Removed the explicit Hibernate flush. Retry number, due time, diagnostic truncation, and transaction boundary remain intact.
- `src/test/java/com/example/relay/attempt/application/AttemptExecutionFencingIntegrationTest.java`
  - Added cases for stale failure after newer success, stale success after newer retry, stale failure after newer retry, stale failure after a retry child terminates, and exactly one retry for the active owner.
  - The terminal-child case asserts there is still only one attempt number 2.
- `src/test/java/com/example/relay/attempt/application/AttemptServiceMarkFailedAndCreateRetryAtomicityTest.java`
  - Rollback injection now also checks the persisted parent status, generation, and claim timestamp, and confirms no child row remains.

## RED / GREEN evidence

- RED: after correcting test-fixture assumptions (retrying parents retain `next_retry_at`; a scheduled child is promoted to `CREATED` before claim), the requested focused tests passed against the pre-change implementation: 12 tests, 0 failures, 0 errors. The existing capability-based skeleton already fenced these scenarios, so there was no honest behavioral RED to report. The missing required integration cases were the unprotected mutation boundary; no false RED was manufactured.
- GREEN: focused tests after the parent-first row-count implementation: 12 tests, 0 failures, 0 errors.
- Full suite: 798 tests, 0 failures, 0 errors, 0 skipped.
- Both commands used the supplied JWT/email environment values. The full suite took 8m20s; the existing `BrevoEmailSenderTest.send_throwsEmailSendException_on429RateLimited` retry test accounted for about 181s.

## Self-review and concerns

- Parent update and retry insert share the `@Transactional` method, so the existing rollback-injection test verifies that a failed child save restores the parent to `IN_FLIGHT`, generation 1, with its claim timestamp present.
- Stale cases assert both the mutation outcome and persisted parent/child counts; the terminal-child case covers duplicate attempt number 2 without adding a P05 allocation constraint.
- The terminal-child setup directly promotes the scheduled retry to `CREATED` to model dispatch before claiming it; dispatcher behavior itself is outside this task.
- No known concerns. No P05 allocation constraint was added.

## Commit

`feat: fence atomic retry creation`.
