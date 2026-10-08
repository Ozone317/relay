# Task 4 report: PostgreSQL recovery race proofs

## Interleavings, transactions, and durable outcomes

`ordinaryRequestCommitsAfterCandidateScanAndWinsWithoutDispatchConfirmation` gates the real candidate finder after it returns exactly T0. Before signalling the gate, the test proxy inspects the actual finder result and requires its candidate IDs to equal `[T0]`. The ordinary request executes `PasswordResetService.issueAndDispatch` on a separate worker and commits T1 before the recovery gate opens. The final state has exactly T0 and T1; T0 is used, T1 is usable but dispatch-unconfirmed, and the sole publication is keyed by T1. No recovery publication or T2 is permitted.

`recoveryWinsThenOrdinaryRequestWaitsOnItsUserLock` holds recovery inside its active `recoverCandidate` transaction, after the production `findByIdForUpdate(T0)` query has completed and before retirement. The actual ordinary issuance service then blocks on its production user lock. After recovery commits and publishes its successor, issuance acquires the user lock, creates and publishes a later successor, and invalidates the recovery successor. The final rows are T0 plus those two successors; only the ordinary successor is usable, and both publications map exactly once to persisted successor IDs.

`dispatchConfirmationOfSelectedCandidateWinsBeforeRecoveryLock` gates recovery after its real candidate scan and, before signalling the gate, requires the actual finder result to equal `[T0]`. It commits `claimResetEmailDispatch(T0)` through a separate `TransactionTemplate`, then releases recovery. T0 remains unused and confirmed, with one row and no recovery publication.

`recoveryWinsThenDispatchConfirmationWaitsOnItsTokenLock` pauses recovery after the real production candidate lock completes, within the same active recovery transaction. A second transaction runs the real `claimResetEmailDispatch(T0)` update and is observed blocked. Once recovery commits, confirmation succeeds. T0 has both `used_at` and `reset_email_dispatched_at`; the one recovery successor is the sole usable row and the sole recovery publication.

The old unordered ordinary-request/recovery test was removed. Review verification had reproduced both legitimate serialization outcomes while the test asserted only one; the explicit request-first and recovery-first proofs now cover the two orderings independently.

`concurrentRecoveryCallersPreserveOneUsableTokenAndPersistedRequestTime` captures each real candidate-finder result independently. Before release, both are asserted to contain exactly T0. After release, exactly one sweeper owns/retire T0 and creates one successor with T0's persisted `first_requested_at`; there is one usable successor and exactly one publication. Concurrent cleanup still removes the expired fixture row.

`recoveryLocksUserBeforeTokenWhileWaitingForTheUser` holds the user's row through the real `UserRepository.lockForUpdate` in an independent transaction. Recovery's PID is captured at entry to the real transactional `recoverCandidate` call and observed blocked on that user row. A third transaction/backend successfully locks T0 while recovery remains blocked on users. Releasing the user holder allows recovery to finish with one successor and one publication.

## Exact backend and blocker evidence

The ownership class no longer substitutes JDBC locks for production repository locks. Its test-only AspectJ advice calls `ProceedingJoinPoint.proceed()` on the real Spring Data `PasswordResetTokenRepository.findByIdForUpdate` proxy first, then records `pg_backend_pid()` and pauses inside the still-active recovery transaction. Consequently the exact production candidate-lock query executes and retains its row lock during both recovery-first races. Ordinary issuance executes the real `UserRepository.lockForUpdate` query.

For recovery-first request ordering, the test identifies distinct recovery and ordinary backend PIDs. It verifies recovery already owns the user row with an independent `FOR UPDATE NOWAIT` probe, then polls `pg_stat_activity` for the exact ordinary PID. The waiter must have `wait_event_type = 'Lock'`, its active query must include `from users` and `for no key update`, and `pg_blocking_pids(ordinaryPid)` must contain the recorded recovery PID.

For recovery-first dispatch confirmation, the recorded recovery and confirmation PIDs must differ. The exact confirmation PID must show `wait_event_type = 'Lock'`, active SQL containing `update password_reset_tokens` and `reset_email_dispatched_at`, and the recovery PID in `pg_blocking_pids(confirmationPid)`.

For user-before-token, the test records the independent user-holder PID, recovery PID, and third token-locking PID separately. It polls the exact recovery PID for a `Lock` wait on SQL containing `from users` and `for no key update`, with the holder PID in `pg_blocking_pids(recoveryPid)`. The third backend's `SELECT ... FOR UPDATE` on T0 completes while that blocker relationship still holds.

The request-first and confirmation-first tests require no waiter: the gated real finder has returned, the competing transaction commits, and only then is recovery released. Their persisted row and publication assertions prove those orderings.

## RED/GREEN and repeat verification

- Review verification reproduced the old unordered race's legitimate two-row outcome against its three-row-only assertion. That race test was removed; both ordered request proofs remain.
- During the real-lock AOP conversion, a focused run was RED because the assertion expected `FOR UPDATE`, while the actual production Hibernate query was `FOR NO KEY UPDATE`. Updating the evidence assertion to the exact emitted lock clause made the focused request/confirmation/sweeper checks green (3 tests).
- The first exact-class run after that conversion was RED: 9 tests, one timeout. The ordinary request was queued on the same single-thread executor as the recovery worker parked at the gate. The ordinary future was moved to a separately tracked executor; this was a test harness correction, not a production change.
- A temporary failure-path harness submitted a worker that threw, invoked teardown, and verified that teardown reported the retained exception only after both its fixture token and user were absent from PostgreSQL. The temporary harness was removed so the requested two-class suite remains the same 9 tests.
- Candidate-scan hardening initially produced a compile RED because the two recovery-first helper call sites also needed the new expected-candidate argument. Those call sites now pass their exact T0 IDs; the focused ordering/sweeper run passed (4 tests).
- Exact suite baseline after that correction: `./mvnw -Dtest=PasswordResetRecoveryOwnershipPostgresTest,PasswordResetMaintenanceConcurrencyPostgresTest test` — 9 tests, 0 failures/errors.
- Three consecutive repeats of the exact command — 9 tests, 0 failures/errors in each run.
- Round 2 after the cleanup and scan-result fixes: the exact two-class command passed 9/9, followed by three consecutive 9/9 passes. The final Surefire reports show 6 ownership tests and 3 maintenance tests, with zero failures/errors/skips.

No sleeps are used. The JWT test secret is supplied through each class's `@TestPropertySource`.

## Timeout and cleanup guarantees

Both classes use named limits: stage latches and PostgreSQL activity polling are 10 seconds; individual futures are 15 seconds; gate holders are 90 seconds; shared cleanup is 30 seconds. The 90-second holder budget exceeds the cumulative coordinator stages (recovery entry, database lock observation, and any third-backend lock completion), while still exceeding the separate future bound. Teardown opens all tracked gates, cancels unfinished futures, requests executor shutdown, and requires every executor to terminate within a shared 30-second deadline. It then joins retained non-cancelled futures within the remaining deadline before touching fixture data.

Each test tracks every executor, submitted future, release gate, created user ID, and created token ID. Teardown releases gates and cancels unfinished futures, then requires all executors to terminate under the shared deadline before inspecting futures or touching PostgreSQL. Completed exceptional futures are retained rather than short-circuiting cleanup. After termination is proven, teardown attempts scoped discovery/deletion of tracked and fixture-owned tokens and deletion of fixture users, then reports retained worker failures. Tracking IDs are cleared only after both deletion calls succeed; if termination cannot be proved, no database cleanup occurs and IDs are not cleared. Setup does not use broad `deleteAll`; cleanup is per-test and scoped, so no fixture depends on a later test or class for removal.

## Adversarial self-review and concerns

- The two request orderings and two dispatch-confirmation orderings each have explicit gates and distinct durable assertions.
- Every required blocker proof ties the exact waiting backend PID to the exact expected blocker PID through `pg_blocking_pids`, requires PostgreSQL `Lock` wait evidence, and checks active SQL for the expected production query.
- Both sweeper candidate results are inspected and required to identify T0 before either sweeper proceeds; request-first and confirmation-first gates likewise inspect the real finder result and require exactly T0 before opening their gates.
- Publication multiplicity is exact and idempotency keys map to durable successor IDs; no test accepts two recovery successors.
- Failure-path cleanup cannot proceed to deleting rows until tracked workers have terminated. Once termination is proven, even exceptional worker futures are reported only after scoped token/user deletion. If a worker exceeds the bounded cleanup deadline, teardown fails rather than racing it with database cleanup.
- No production source, schema, API, or configuration was changed. The remaining test-only AOP advice observes and pauses after the actual Spring Data proxy call; it does not supply the lock itself.
- Maven emitted existing dynamic-agent, Spring Security, and open-in-view warnings; these did not affect the successful test outcomes.
