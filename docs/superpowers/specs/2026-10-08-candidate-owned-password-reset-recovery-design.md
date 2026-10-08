# Candidate-Owned Password-Reset Recovery Design

**Date:** 2026-10-08
**Status:** IMPLEMENTED — PENDING OWNER REVIEW
**Investigation:** `docs/reviews/2026-10-08-password-reset-recovery-investigation.md`

## Intent

Make password-reset email recovery conditional on the continued eligibility of the exact token row observed by the sweeper. Preserve the existing API, token, activation, session-revocation, rate-limit, retry-bound, and email-dispatch contracts.

## Invariant

> Recovery may supersede only the specific recovery chain that remains eligible at the transaction's authoritative decision point. A stale recovery observation must never invalidate a newer user request or a dispatch-confirmed token.

Corollaries:

- a consumed, superseded, expired, no-longer-stale, or dispatch-confirmed candidate is a losing recovery race;
- a losing race is a durable no-op and publishes no email;
- at most one recovery transaction may replace a given candidate;
- the successor inherits the locked candidate's durable `first_requested_at`;
- an exhausted chain is retired, never restarted with a fresh chain timestamp;
- retirement and successor insertion are atomic;
- email publication occurs only after a committed successor exists.

## Approaches considered

### 1. Candidate row re-read under User→token locks — recommended

Pass the observed candidate ID and user ID into a new transactional recovery method. Lock the user first, then load the exact candidate `FOR UPDATE`. Evaluate all eligibility and exhaustion fields from that locked row. Retire only that candidate and insert its successor in the same transaction.

Advantages:

- candidate identity is explicit and authoritative;
- the locked row supplies the durable chain timestamp without trusting the stale entity;
- dispatch confirmation and consumption naturally serialize on the candidate row;
- Java control flow can return clear `REISSUED`, `EXHAUSTED`, or `LOST_RACE` outcomes;
- no migration or new infrastructure.

Cost: one primary-key locked read per candidate. At the configured batch size and interval this is bounded and indexed by the primary key.

### 2. Conditional exact-candidate update after the user lock

Issue one `UPDATE ... WHERE id = :candidateId AND ...` and proceed only when its affected-row count is one.

This can be correct, but the successor must inherit the database row's authoritative `first_requested_at`. A plain affected-row method would trust the stale snapshot; obtaining the timestamp requires `UPDATE ... RETURNING` and a custom repository fragment/JDBC mapping. That is more plumbing than the locked-read approach without a demonstrated performance need.

### 3. New claim state, partial uniqueness, distributed lock, or singleton ownership

A recovery state/generation column or partial unique live-token index could add defense in depth, but neither is needed to express the invariant. A singleton scheduler does not serialize against user requests. Redis/distributed locks introduce a second authority. Leader election and a generic outbox solve different problems. These approaches are rejected for this project.

## Proposed protocol

### Scan phase

Keep the current bounded, unlocked finder as a source of *hints*. A returned entity grants no mutation authority.

For each hint, pass only:

- `candidateId`;
- `observedUserId`;
- configured `grace`;
- configured `maxRecoveryWindow`.

Do not pass the stale `User` entity or stale `firstRequestedAt` into issuance.

### Authoritative recovery transaction

Add a single `@Transactional` method to `PasswordResetTokenService`, conceptually:

```java
RecoveryAttempt recoverCandidate(
        UUID candidateId,
        UUID observedUserId,
        Duration grace,
        Duration maxRecoveryWindow)
```

Protocol:

1. `userRepository.lockForUpdate(observedUserId)`. If the user no longer exists, return `LOST_RACE`.
2. `passwordResetTokenRepository.findByIdForUpdate(candidateId)`. This must be an explicit primary-key `SELECT ... FOR UPDATE` on `password_reset_tokens`.
3. After both locks are held, capture `decisionTime = Instant.now()` and derive:
   - `staleBefore = decisionTime.minus(grace)`;
   - `recoveryCutoff = decisionTime.minus(maxRecoveryWindow)`.
4. Return `LOST_RACE` unless the locked row:
   - belongs to `observedUserId`;
   - has `used_at IS NULL`;
   - has `reset_email_dispatched_at IS NULL`;
   - has `expires_at > decisionTime`;
   - has `updated_at < staleBefore`.
5. If `first_requested_at <= recoveryCutoff`, retire that exact row using the existing conditional `giveUpOn` update and return `EXHAUSTED`. Do not insert or publish.
6. Otherwise retire that exact row using `giveUpOn(candidateId, decisionTime)`. Require an affected-row count of one; zero is `LOST_RACE`.
7. Generate the new raw token, insert a successor for the locked user, and carry forward the locked row's `first_requested_at`.
8. Return `REISSUED` with the successor/raw-token pair. Spring commits on proxy return.

The recovery path must not call `invalidateAllForUser`. Exact candidate retirement is the ownership operation. Ordinary user issuance retains blanket invalidation, because its product contract is deliberately “new user request supersedes all older unused requests.”

### Exact candidate-lock repository query

Add this repository method with this exact native SQL shape:

```java
@Query(value = """
        SELECT *
        FROM password_reset_tokens
        WHERE id = :tokenId
        FOR UPDATE
        """, nativeQuery = true)
Optional<PasswordResetToken> findByIdForUpdate(UUID tokenId);
```

The primary-key equality predicate ensures PostgreSQL locks only the matching token row; there is no join, user predicate, range predicate, or candidate scan in this statement. Hibernate may subsequently resolve the entity's eager `User`, but recovery must already hold that user's row lock and must construct the successor from the `User` returned by `UserRepository.lockForUpdate`.

Verification is required at two levels:

- a PostgreSQL repository test holds the returned transaction open, proves a second transaction can lock a different token row for the same user, and proves it cannot lock the selected token until release;
- a service-level PostgreSQL test holds the user row before recovery starts, binds recovery to its exact backend PID, observes that backend blocked on the user-lock SQL, and proves it has not yet reached/locked the candidate. Only after releasing the user blocker may the backend execute the candidate `FOR UPDATE` query.

These tests verify both row scope and User-before-token ordering instead of inferring them from annotations.

### Result and publication

Use an explicit outcome rather than `null` or exceptions for expected race loss:

```java
enum RecoveryOutcome { REISSUED, EXHAUSTED, LOST_RACE }

record RecoveryAttempt(RecoveryOutcome outcome, IssuedResetToken issuedToken) {}
```

`issuedToken` is non-null only for `REISSUED`; factory methods should enforce that construction invariant.

`PasswordResetService.issueAndDispatchForRecovery` calls the transactional method through its Spring proxy. Only after it returns `REISSUED` does it build and publish the email message. `EXHAUSTED` and `LOST_RACE` publish nothing. The sweeper logs the returned outcome without issuing separately.

## Protected decision ordering and durable visibility

Candidate-row locking establishes the protected decision ordering: after the transaction has acquired the user lock and the exact candidate row lock, no competing confirmation, consumption, issuance, or second recovery can pass the relevant protected state transition until this transaction ends. The locked re-read is the authoritative decision point and determines which competitor is ordered first.

Transaction commit is the separate durable visibility point. Retirement and successor insertion become visible together only when the transaction commits. A rollback releases the locks without making either change visible. The implementation and tests must not describe lock acquisition itself as durable replacement.

At PostgreSQL READ COMMITTED:

- If an ordinary request acquires the user lock first, it invalidates T0 and commits T1. Recovery then acquires the user lock, locks T0, observes `used_at`, and loses without touching T1.
- If recovery acquires the user lock first, it locks/validates T0, retires it, and inserts T2. A later ordinary request waits, then legitimately supersedes T2. The newer user request remains the final winner.
- If dispatch confirmation updates T0 first, recovery's `SELECT ... FOR UPDATE` waits and then observes `reset_email_dispatched_at`, so recovery loses.
- If recovery locks T0 first, confirmation waits. Recovery is the authoritative winner; it retires T0 and creates the successor atomically. The later confirmation may annotate retired T0 under the existing confirmation contract, but cannot cause a second recovery.
- If consumption acquires the user lock first, it consumes T0 and recovery later loses. If recovery acquires both locks first, consumption of T0 later fails as a normal superseded-token result.

The combination is linearizable: the locked decision orders competitors, and commit publishes the atomic durable result of that decision.

## Lock order and deadlock analysis

Required order remains:

```text
users(id) FOR NO KEY UPDATE
    -> password_reset_tokens(candidateId) FOR UPDATE
    -> exact candidate UPDATE
    -> successor INSERT
```

Competing paths:

- ordinary reset issuance: user → token bulk update → token insert;
- password reset consume: user → exact token update → user/other token/refresh updates;
- email verification activation: user → verification/reset token updates;
- recovery: user → exact token;
- dispatch confirmation: token only;
- cleanup: expired token delete only.

All paths that acquire both resource classes remain user-first. Token-only confirmation/cleanup never request a user lock, so they cannot complete an AB-BA cycle. Two recoveries for one user serialize on the user row before either requests a token row. Two different users touch different user/token rows.

The explicit native locked read should target only `password_reset_tokens`, avoiding a Hibernate eager-join lock whose SQL/lock scope is provider-sensitive. Loading the associated user after its row is already locked is safe, but the new successor should use the `User` returned by `lockForUpdate`, not a stale entity from the scan.

## Failure and rollback semantics

- Any exception after exact retirement and before commit rolls back both retirement and successor insertion.
- Token generation failure before persistence also rolls back retirement.
- A commit failure prevents the proxied method from returning, so `PasswordResetService` cannot publish.
- Rabbit publication failure after commit retains the new undispatched successor, which becomes eligible for the existing bounded recovery mechanism.
- A process crash after commit but before publication has the same recoverable durable state.
- A process crash after provider acceptance but before dispatch confirmation remains an ambiguous-send case already handled by provider idempotency and recovery; the database cannot prove inbox delivery.
- Expected race loss is not an exception and does not retry within the same sweep. A later scan evaluates current durable state.

## Eligibility and boundary semantics

Preserve current strict comparisons:

- candidate must have `expires_at > decisionTime`;
- candidate must have `updated_at < decisionTime - grace`;
- recovery is exhausted when elapsed time is greater than or equal to the window, equivalently `first_requested_at <= decisionTime - maxRecoveryWindow`.

All comparisons occur after both locks from the locked row. This prevents time spent waiting for a lock from extending eligibility and prevents a stale snapshot from restarting the window.

Boundary regressions must not rely on wall-clock sleeps or timing margins. Use Mockito's test-only static control of `Instant.now()` in synchronous service tests to supply one fixed post-lock decision instant, then persist candidates exactly one nanosecond before, exactly at, and exactly one nanosecond after each boundary. Do not add a production `Clock`, time-provider interface, configuration key, or schema field solely for these tests. PostgreSQL characterization tests cover locking and durable outcomes; focused service tests pin the pure comparison boundaries.

## Compatibility

Preserved without HTTP or schema changes:

- hashed, single-use tokens;
- generic enumeration-resistant request response;
- Redis cooldown/hourly admission behavior;
- 30-minute token TTL and configured recovery grace/window/batch;
- PENDING activation and first-activation-wins behavior;
- refresh-session revocation on consumption;
- post-commit Rabbit publication and provider idempotency keys;
- scheduler architecture and maintenance executor;
- password-reset endpoint contracts.

No generic outbox, new auth mechanism, verification-email recovery, account restructuring, distributed lock, leader election, or state-machine migration is introduced.

## Schema and rollout

No migration is required. The candidate is read by its existing primary key; candidate discovery continues to use the existing partial recovery index.

**Mixed-version scheduler restriction:** Old and new password-reset recovery schedulers must not execute concurrently. Stop or disable recovery scheduling on every old instance and wait until every in-flight old recovery execution has drained or been terminated before enabling any new recovery scheduler. Ordinary request traffic may continue while recovery scheduling is disabled. Undispatched candidates remain durable and will be recovered after re-enable. This is a rollout gate, not a request for leader election or scheduler redesign.

## PostgreSQL regression matrix

| Test | Initial durable state | Controlled interleaving / locks | Expected durable outcome | Expected publication |
|---|---|---|---|---|
| Historical stale T0 / confirmed T1 | T0 eligible | Sweeper query returns T0 and pauses; request transaction user→tokens creates T1; confirmation updates T1; recovery resumes user→T0 | T0 used, T1 unused+confirmed, no T2 | T1 only; losing recovery publishes none |
| Newer unconfirmed T1 | T0 eligible | Pause after selection; request commits T1 without confirmation; resume recovery | T1 remains sole usable token | User-request T1 only |
| Confirmation of selected T0 | T0 eligible | Pause after selection; confirmation commits on T0; resume recovery | T0 remains unused+confirmed; no successor | No recovery publication |
| Recovery wins against T0 confirmation | T0 eligible | Recovery holds user→T0 and pauses before retirement; confirmation starts on a separate backend and is observed blocked by recovery; release recovery | T0 retired and one successor committed; confirmation may annotate retired T0 afterward | Exactly one recovery publication |
| Consumption of selected T0 | T0 eligible with known raw token | Pause after selection; consume transaction user→T0 commits; resume recovery | T0 used; password/session/activation effects preserved; no successor | No recovery reset-link publication |
| Two sweepers, same T0 | T0 eligible | Both scans return T0; release together; observe separate backends serialize user→T0 | Exactly one successor; T0 retired once | Exactly one successor publication |
| Legitimate recovery liveness | T0 eligible and unchanged | Recovery locks user→T0 with no competitor | T0 retired; exactly one usable successor carrying original `first_requested_at` | Exactly one successor publication after commit |
| Exhausted chain | T0 eligible by grace but `first_requested_at` at/before cutoff | Lock user→T0; authoritative time check | T0 retired in place; no successor | None |
| Recovery rollback | T0 eligible | Lock user→T0; exact retirement succeeds; force successor flush failure | Transaction rollback leaves T0 unused/unconfirmed and no successor | None |
| Recovery wins then user requests | T0 eligible | Recovery locks first and commits T2; user request then obtains user lock | User request supersedes T2 and leaves its T1-equivalent sole usable | One recovery email and one later user-request email; final link is user's |
| Boundary values | Candidate at exact grace/expiry/window edges | No concurrency; transaction reads locked row | Strict comparison behavior matches section above | Only eligible case publishes |

The ordinary-request and dispatch-confirmation races each require both orderings as permanent PostgreSQL regressions. Where one side must wait on a held row lock, the test records both backend PIDs and uses `pg_stat_activity` plus `pg_blocking_pids` to prove the intended blocker; thread start order alone is not evidence of the database interleaving.

Tests must inspect durable rows and captured publications. They must use latches/barriers or intercepted repository boundaries, never sleeps. Lock-sensitive tests should bind observations to exact PostgreSQL backend PIDs where blocking itself is asserted.

## Adversarial self-review

### Candidate identity

The transaction receives T0's ID, locks T0 by primary key, validates its user ID, and retires only T0. No code path can substitute “whatever token is live for the user.”

### Competing issuance paths

Every path that both touches a user and reset/verification tokens already locks the user first. The proposed path preserves that order. The candidate scan remains unlocked because it is only advisory.

### Dispatch race

The token row lock makes confirmation and recovery mutually exclusive at the decision. Eligibility is checked after acquiring the lock, not from the scan snapshot.

Both directions are tested: confirmation-before-lock produces `LOST_RACE`; recovery-holds-lock makes confirmation visibly block until recovery commits/rolls back.

### Duplicate emails

Two stale sweepers cannot both retire T0. The loser receives `LOST_RACE`, and publication is conditional on `REISSUED`. This removes today's two-publication behavior.

### Deadline extension

The successor uses locked T0's durable `first_requested_at`; deadline evaluation uses a post-lock decision time. A new ordinary user request intentionally begins a new chain, but stale recovery cannot transform the old chain into it.

### Rollback

Exact retirement and successor insert share one transaction. Publication remains outside and after the proxy commit. No partial replacement survives rollback.

### Deadlocks

No two-resource path reverses user→token. Token-only writers do not later acquire users. The native candidate lock avoids provider-dependent multi-table locking.

### Test quality

The permanent tests gate after the actual finder returns and before the transactional recovery call. They assert committed row identities/timestamps and captured publish calls, so they reproduce the dangerous interleaving rather than merely starting concurrent threads.

Recovery-wins tests additionally gate inside the recovery transaction after `findByIdForUpdate` and prove the competing PostgreSQL backend is blocked by the expected recovery backend. Fixed-time service tests pin exact grace, expiry, and max-window comparisons without sleeps or new production time abstractions.

## Acceptance criteria

- The historical T0/T1/T2 test passes with T1 still usable and no T2/publication.
- Confirmation or consumption of selected T0 causes a no-op.
- Two sweepers create at most one successor and one email.
- A valid unchanged candidate still recovers.
- Exhaustion is authoritative and cannot be restarted by recovery.
- Forced rollback preserves T0 and publishes nothing.
- User-before-token ordering is asserted and PostgreSQL blocking tests complete without deadlock.
- No migration, endpoint, response, rate-limit, activation, refresh-session, or email architecture change is introduced.
- The focused and relevant regression suites are green.
