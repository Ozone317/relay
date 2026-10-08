# Task 4 report: PostgreSQL recovery race proofs

## Interleavings and transaction boundaries

`ordinaryRequestCommitsAfterCandidateScanAndWinsWithoutDispatchConfirmation` holds recovery immediately after the real candidate scan returns T0. The ordinary `issueAndDispatch(user)` call completes its `PasswordResetTokenService.issue` transaction, then publishes T1. Recovery is released only after that commit/publication. The durable result is exactly T0+T1: T0 used, T1 unused and dispatch-unconfirmed, and exactly one publication keyed by T1. The two-row assertion excludes T2.

`recoveryWinsThenOrdinaryRequestWaitsOnItsUserLock` pauses recovery after it owns T0, within the transactional `recoverCandidate` call and before that method returns/commits. Its transaction already owns the user row and T0. Ordinary issuance starts on a separate backend and is observed waiting on the user lock held by recovery. Recovery is released, commits its T0 retirement and successor, and publishes that successor; the ordinary request then commits its own successor, invalidating the recovery successor, and publishes its own token. The final rows are T0 plus two successors; only the ordinary successor is usable, and exactly two publications map to those committed successors.

`dispatchConfirmationOfSelectedCandidateWinsBeforeRecoveryLock` gates after the real candidate scan, commits `claimResetEmailDispatch(T0)` using `TransactionTemplate`, then releases recovery. T0 remains unused but dispatch-confirmed, no successor row is added, and no recovery publication occurs.

`recoveryWinsThenDispatchConfirmationWaitsOnItsTokenLock` pauses recovery after it locks T0 inside its transaction. A second `TransactionTemplate` backend attempts the dispatch-confirmation update and is observed blocked by recovery. After recovery commits, confirmation commits. T0 has both `used_at` and `reset_email_dispatched_at`; the one recovery successor is the sole usable row and the sole recovery publication.

`concurrentRecoveryCallersPreserveOneUsableTokenAndPersistedRequestTime` retains both real finder-return gates until both sweepers selected T0. After release, exactly one candidate owner retires T0, inserts one successor with T0's persisted `first_requested_at`, and publishes once. Concurrent cleanup removes the expired row. No second successor or publication is accepted.

`recoveryLocksUserBeforeTokenWhileWaitingForTheUser` holds the user's row in an independent transaction, captures recovery's PID at entry to `recoverCandidate`, and observes recovery blocked on the users `FOR NO KEY UPDATE` query. A third transaction locks T0 and commits while recovery remains blocked on users. Once the user lock is released, recovery completes with one successor and one publication. This demonstrates user-before-token order at the service boundary.

## Backend and blocker evidence

Each required waiter has a PID captured from `SELECT pg_backend_pid()` on its own transaction connection. The request-first/recovery-first gate records recovery's PID within the active `recoverCandidate` transaction after T0 is locked. The ordinary request PID is captured inside its active `issue` transaction; the confirmation PID is captured inside the `TransactionTemplate` that issues the update. The user-before-token test captures recovery and user-holder PIDs inside their respective transactions and captures the third token-locking PID separately.

The blocking assertions poll `pg_stat_activity` by the exact waiting PID and require `wait_event_type = 'Lock'`, the expected active SQL (`FROM users ... FOR UPDATE` for ordinary issuance or `UPDATE password_reset_tokens ... reset_email_dispatched_at` for confirmation), and the expected recovery PID in `pg_blocking_pids(waitingPid)`. User-before-token evidence also checks the exact recovery PID is blocked by the user-holder PID on `FROM users ... FOR NO KEY UPDATE`. The third backend's successful T0 lock future must complete while that wait remains present.

The ownership test uses test-only `JdbcTemplate` lock interceptors for Spring Data's abstract `findByIdForUpdate` and `lockForUpdate` repository methods. They issue the equivalent `SELECT ... FOR UPDATE` on the caller's transaction connection, then load the entity through the real repository. This permits deterministic pause points while the row lock is held and makes the active PostgreSQL query visible. Candidate scanning, service transactions, token updates/inserts, dispatch service calls, and durable assertions remain real.

## RED/GREEN evidence

The first focused run exposed a compile RED (missing `Map` import), then a harness RED: 3 tests ran with 2 latch timeouts and 1 Mockito error because an abstract Spring Data query method has no callable real implementation. After switching that repository boundary to the explicit transactional row lock, the ordinary/recovery PID proof still failed because the request waiter was not observable through the abstract user repository spy. Replacing that lock interception with the same explicit test SQL produced the required PostgreSQL `Lock` wait and blocker PID. The first focused GREEN then passed; subsequent full-class runs confirmed all four orderings and maintenance proofs.

Final suite command: `./mvnw -Dtest=PasswordResetRecoveryOwnershipPostgresTest,PasswordResetMaintenanceConcurrencyPostgresTest test`.

- Initial full run: 10 tests, 0 failures/errors.
- Three consecutive repeat runs: 10 tests each, 0 failures/errors each.
- Final full run after cleanup/assertion review: 10 tests, 0 failures/errors.

No sleeps are used. Latches have 10-second bounds; PostgreSQL activity polling is bounded at 10 seconds with a 10 ms poll interval; future waits are bounded at 10 or 15 seconds. Every new gate releases in `finally`, executor shutdown is followed by bounded termination checks, and recovery gates release before shutdown. The earlier scan-gated test now also awaits worker termination during cleanup.

## Adversarial self-review and concerns

- The two request orderings remain distinct: ordinary commit after scan versus recovery owning T0 first and forcing the ordinary request to wait on the user row.
- The two confirmation orderings remain distinct: committed confirmation after scan versus confirmation blocked behind recovery's T0 lock.
- No assertion accepts two recovery successors. The two-sweeper test requires exactly one successor, one usable row, one publication, and persisted request-time preservation.
- Publication assertions tie each emitted idempotency key to a persisted committed token. Recovery publications occur only after the transactional service call returns; ordinary issuance likewise publishes after its transaction.
- The normal test run emitted existing JVM/Mockito, Spring Security, and open-in-view warnings; test results themselves were green.
- The main limitation is the test-only repository lock interception noted above: it exercises the same PostgreSQL row-lock boundary but replaces the repository's abstract lock-query invocation so the tests can pause at that exact boundary. No production code, schema, API, or configuration was changed.
