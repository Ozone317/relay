# P05 — Concurrent Attempt Allocation and Replay Sequence Correctness Design

**Date:** 2026-10-04

**Status:** Approved; Tasks 1–6 verified including owner-authorized V14 correction; merge/deployment approval pending

**Source revision:** `a7148d7` on `main` (`origin/main` at the same revision)

**Scope:** delivery-scoped Attempt allocation, replay eligibility linearization, automatic-retry compatibility,
database sequence constraints, migration, and deterministic concurrency coverage

**Out of scope:** API idempotency, replay budgets, retry policy changes, P04 lease renewal, P06 scheduler ownership,
exactly-once webhook effects, API keys, tiers, quotas, billing, and unrelated schema cleanup

## 1. Decision summary

P05 is still a real defect on the merged P04 code. A deterministic PostgreSQL probe against current production
services paused replay A immediately before `AttemptService.createReplay`, let replay B create Attempt 7 and complete it
successfully through the P04 claim/completion protocol, then released A. The committed history was:

```text
#6 DEAD       generation=0
#7 SUCCEEDED  generation=1
#7 CREATED    generation=0
```

The active-row index did not reject A because B's Attempt was terminal by then. The `delivery_status` view has no
tie-breaker after `attempt_no`, so two rows were both eligible to be treated as latest. The new `CREATED` row was also
fresh executable work even though the competing replay had already succeeded.

Relay will serialize every post-initial Attempt allocation on the immutable parent `deliveries` row with PostgreSQL
`SELECT ... FOR UPDATE`. Any transaction appending an Attempt to an existing Delivery must first protect the referenced
Endpoint, establishing a global **Endpoint-before-Delivery** parent-lock hierarchy. Replay uses Endpoint `FOR SHARE`
because Endpoint activity is an eligibility predicate. Retry uses the weaker Endpoint `FOR KEY SHARE` solely to order
against Endpoint deletion; activity is not a retry predicate. While holding the Delivery row lock, the transaction
reads current history, calculates `MAX(attempt_no) + 1`, inserts the new Attempt, and commits.

PostgreSQL 16 probes reproduced two independent deadlocks against Endpoint deletion. The original replay order,
`Delivery FOR UPDATE -> Endpoint FOR SHARE`, closes a cycle with deletion's
`Endpoint DELETE -> Delivery FK examination`. Reversing replay alone is insufficient: the originally proposed retry
order, `Delivery FOR UPDATE -> Attempt INSERT -> implicit Endpoint FK KEY SHARE`, closes the same cycle. PostgreSQL's
choice of deadlock victim is not a correctness mechanism, so both allocation paths must follow the global hierarchy.

Migration V13 will add:

```sql
CHECK (attempt_no >= 1)
UNIQUE (delivery_id, attempt_no)
```

The unique `(delivery_id, attempt_no)` B-tree is expected to support the descending latest-Attempt lookup through a
backward scan, but P05 will not remove `idx_attempts_delivery_attempt_no` on theory alone. The Task 1 migration
checkpoint must capture a representative PostgreSQL 16 plan for the actual query before the destructive decision. If
that access path is not clearly demonstrated, V13 retains the existing ordering index; its removal is optional cleanup,
not a P05 correctness requirement. The partial active-row unique index remains because it expresses a different
invariant: at most one active Attempt per logical Message/Endpoint delivery.

**Task 1 checkpoint (2026-10-04):** PostgreSQL 16.15 chose `Index Scan Backward using
uk_attempts_delivery_attempt_no` for the actual `WHERE delivery_id = ? ORDER BY attempt_no DESC LIMIT 1` lookup after
`idx_attempts_delivery_attempt_no` was dropped in a disposable schema with 101 Deliveries and 2,002 Attempts. The plan
had no Sort node. V13 therefore drops the old ordering index after adding the unique constraint; the active-row index
remains. The complete plan and setup are recorded in the Task 1 report.

**Task 6 discovery (2026-10-05):** The positive production-writer audit found only initial fan-out, replay, and retry;
both append paths implement Endpoint-before-Delivery locking. The Task 1 backward-scan evidence still holds for one
Delivery, but it does not establish performance for
the `delivery_status` mixed ordering `(delivery_id ASC, attempt_no DESC)`. On PostgreSQL 16.15 with 5,000 Deliveries
and 100,000 Attempts, five warm exact list/count query runs gave median list/count times of 174.729/81.846 ms with
V13's index shape versus 136.423/33.024 ms with the old DESC index present. The count gains an Incremental Sort over
all Attempts after the index removal. Task 6 stopped and the owner authorized forward-only V14 to restore that exact
DESC index. V13 history is unchanged. V13's unique index enforces correctness; V14's mixed-order index serves the
existing list/count read pattern. Their shared columns do not make them redundant: ordering and purpose differ.
The migration test proves exact serialized Attempt-history preservation across V12 -> V13 -> V14, and the V14
count plan regains the mixed-order Index Only Scan without the extra Incremental Sort. Full before/after plans,
timings, final gates, and rollout conditions are in `docs/reviews/2026-10-04-p05-verification.md`.

**Final Task 6 result:** PostgreSQL 16.15 steady before/after-V14 medians were 191.266/152.380 ms for the list and
88.085/36.849 ms for the count; both final indexes and both permanent constraints exist. The representative 100,000
Attempt row count and serialized-history digest are unchanged. The prescribed focused selection passed 79 tests,
supplemental native/P04/lifecycle/service gates passed 56, and the practical full Docker suite passed 856 with only
the confirmed unrelated Brevo 429 method excluded (its other 10 tests pass). Scoped Java formatting and diff checks
pass; the global formatting command still reports 179 existing violations. No external database was audited or deployed.

## 2. Sources and method

The original investigation used `main` at `a7148d7`. Historical readiness documents, the Delivery design, the PRD, and
the approved P04 design/plan recovered intent and explicit decisions; baseline findings were verified against Java,
Flyway V1–V12, tests, and PostgreSQL 16. Task 6 repeats the positive source/lock audit on the implemented P05 branch
and verifies final Flyway V1–V14 schema and tests. Historical reproductions below retain their baseline context.

The production-writer audit searched for:

- every `new Attempt(...)`;
- every `AttemptRepository.save*` call;
- SQL `INSERT`/`UPDATE` against `attempts`;
- all `attempt_no` calculations and orderings;
- every `@Transactional` boundary surrounding those operations;
- schedulers, reconciliation code, and migrations that can create or mutate Attempts.

The Graphify index was used for navigation, then every relevant result was checked directly because the graph and
historical documents are not source-of-truth substitutes.

## 3. Verified current model

### 3.1 Domain relationships

```text
User
└── Environment
    └── App
        ├── Event                         catalog/routing type
        ├── Endpoint                      outbound destination
        ├── Subscription                  unique Event ↔ Endpoint route
        └── Message                       one accepted JSON occurrence
            └── Delivery                  unique (Message, Endpoint) act
                └── Attempt 1..N          physical HTTP executions/retries/replays
```

`MessageService.create` selects active subscriptions for the requested Event. In one transaction it inserts the
Message, one Delivery per selected Endpoint, and each Delivery's first Attempt. `deliveries` has a unique
`(message_id, endpoint_id)` index, so a Delivery is the stable logical history for one Message/Endpoint pair.

Automatic retry and manual replay carry the same `delivery_id`; neither creates a new Delivery. An Attempt represents
one physical execution record and its durable lifecycle/diagnostics. Recovery can make the same Attempt executable
again under a new P04 execution generation, but it does not create another Attempt and does not consume another
attempt number.

### 3.2 `attempt_no` semantics

`attempt_no` is a one-based ordinal scoped to one Delivery. It is not scoped to an Endpoint globally, Message globally,
Subscription, or application instance. Initial fan-out writes 1. Automatic retry and manual replay share the same
sequence.

Attempt number does not independently label the creator type. There is no `retry`/`replay` discriminator column. In
the current six-attempt policy, Attempts 1–6 may participate in automatic retry. A manual replay after exhaustion is 7
or greater; on a failure it is immediately final because `DeliveryWorker` treats every attempt number at or above
`RetryTier.MAX_ATTEMPTS` as final. This behavior is explicitly covered by current replay lifecycle tests and remains
unchanged.

The committed sequence is intended to be positive, unique, strictly increasing, and contiguous. Existing design text
calls it gap-free, and current consumers rely on that shape:

- `delivery_status` selects the latest Attempt by highest `attempt_no`;
- Delivery summaries expose `latestAttemptNo` and `attemptCount`;
- attempt-history APIs sort by `attemptNo`;
- `DeliveryWorker` derives retry tier and finality from the number;
- replay history keeps increasing on the same Delivery.

The pre-P05 schema enforced none of positivity, ordinal uniqueness, or contiguity. V13 permanently enforces
positivity and ordinal uniqueness; contiguity remains a production-writer protocol. V14 restores the ordering index
for read performance separately from those correctness constraints.

### 3.3 Presentation contract

The Delivery API exposes one stable Delivery ID, derived latest status, `attemptCount`, `latestAttemptNo`, and a
paginated Attempt history. Attempt detail exposes the number but not its allocation source. If duplicate numbers exist:

- `delivery_status`'s `ORDER BY delivery_id, attempt_no DESC` has no deterministic winner;
- `latestAttemptNo` can stay constant while `attemptCount` increases;
- a stale replay can become fresh executable work after another replay succeeded;
- UI/API consumers cannot identify a single physical ordinal 7.

P05 therefore protects a customer-visible identity/history contract, not merely an internal cosmetic counter.

## 4. Complete production Attempt-creator inventory

| Creator | Initiator and transaction | Parent Delivery | Current allocation | Current serialization/constraint | Contention and required result |
|---|---|---|---|---|---|
| Initial fan-out | `MessageService.create` outer transaction calls `AttemptService.createFromSubscriptionList`; Message, Deliveries, and Attempts commit together | Newly inserted, unique `(message_id, endpoint_id)` | fixed `1` | new UUID Message and new Delivery; Delivery unique index | No production path can concurrently append to the not-yet-committed new Delivery. Keep fixed 1; V13 validates it |
| Automatic retry | `DeliveryWorker` calls proxied transactional `AttemptService.markFailedAndCreateRetry` | existing Delivery from the claimed Attempt | current `MAX(attempt_no) + 1` after ordered locks and successful P04 parent fence | Endpoint `FOR KEY SHARE` -> Delivery `FOR UPDATE` -> exact positive-generation P04 parent UPDATE; V13 unique backstop | Same transaction inserts SCHEDULED generation-0 child; zero-row stale fence inserts nothing; rollback consumes no number |
| Manual replay | HTTP `POST /deliveries/{id}/replay` calls non-transactional `DeliveryReplayService` for ownership only, then proxied transactional `AttemptService.createReplay(Delivery)` | existing Delivery | current `MAX(attempt_no) + 1` after serialized eligibility | Endpoint `FOR SHARE` and current activity -> Delivery `FOR UPDATE` -> current latest DEAD/active guard; V13 unique backstop | Same transaction inserts CREATED generation-0 work and holds both parent locks through commit; response view is first read after commit |

No other production Attempt creator exists:

- `ReconciliationSweeper` resets stale `IN_FLIGHT` rows to `CREATED`; it does not insert.
- `RetryScheduler`/`ReadyWorkRepository` promote or lease existing rows; they do not insert.
- ready publication, dead-letter notification, and recovery mutate existing rows only.
- P04 claim/completion SQL mutates existing rows only.
- Flyway migrations V1–V12 create/backfill schema and Delivery links but do not insert historical Attempts after V1
  establishes the table.

Test fixtures and the temporary investigation probe insert Attempts but are not production writers.

## 5. Current database contract

Final relevant constraints/indexes after V13 and V14 are:

- primary key `attempts(id)`;
- non-null foreign key `attempts.delivery_id -> deliveries(id)`;
- unique `deliveries(message_id, endpoint_id)`;
- positive check `attempts_attempt_no_positive CHECK (attempt_no >= 1)`;
- unique `uk_attempts_delivery_attempt_no(delivery_id, attempt_no)`, backed by its ascending B-tree index;
- non-unique V14 `idx_attempts_delivery_attempt_no(delivery_id, attempt_no DESC)` for mixed-order list/count reads;
- generated `active_endpoint_id` plus unique
  `idx_attempts_one_active_per_message_endpoint(message_id, active_endpoint_id)` for statuses `CREATED`, `IN_FLIGHT`,
  or `SCHEDULED`;
- status, execution-generation, and `IN_FLIGHT`/claim-time consistency checks.

Before P05 there was no ordinal unique/positive constraint. The active-row index alone did not constrain ordinal
identity: it permitted terminal duplicates and a new active duplicate after the first row became terminal.
V13 supplies the separate permanent sequence backstops; V14's DESC index supplies no correctness constraint.

V13 permanently enforces only the invariants PostgreSQL can express directly: `CHECK (attempt_no >= 1)` permanently
enforces positivity, and `UNIQUE (delivery_id, attempt_no)` permanently enforces physical ordinal uniqueness. The
database does **not** permanently enforce `1..N` gaplessness.

The repository cannot prove the contents of an external deployment database. V13 must therefore be preceded by and
itself fail on these audit conditions:

```sql
-- Exact duplicates that make the unique constraint impossible.
SELECT delivery_id, attempt_no, COUNT(*) AS row_count, array_agg(id ORDER BY created_at, id) AS attempt_ids
FROM attempts
GROUP BY delivery_id, attempt_no
HAVING COUNT(*) > 1;

-- Non-positive or non-contiguous histories.
SELECT delivery_id, MIN(attempt_no), MAX(attempt_no), COUNT(*)
FROM attempts
GROUP BY delivery_id
HAVING MIN(attempt_no) <> 1
    OR MAX(attempt_no) <> COUNT(*)
    OR BOOL_OR(attempt_no < 1);
```

Duplicate repair must not be automated by deleting an arbitrary row: duplicate ordinals can contain different
outcomes and one can have caused a real receiver effect. A non-empty audit is a rollout blocker requiring an explicit
operator/data-owner decision and preserved forensic export.

The contiguity preflight proves only that histories are clean at migration time. After migration, contiguity is an
application protocol maintained by the Delivery serialization lock, current-max-plus-one calculation, insertion in
the same transaction, and rollback semantics.

## 6. Fresh reproduction on merged P04

### 6.1 Harness

A temporary untracked `@SpringBootTest` used the shared PostgreSQL 16 Testcontainer and real
`DeliveryReplayService`, `AttemptService`, repositories, Flyway V1–V12, and P04 claim/completion SQL. A Mockito spy
only supplied a latch immediately before the real `AttemptService.createReplay` call for the thread named
`delayed-replay`. The temporary source was removed after the run. P00 defaults suppressed autonomous schedules and
Rabbit listeners.

Both probe cases passed their evidence assertions (`2` tests, `0` failures/errors).

### 6.2 Damaging replay/replay interleaving

```text
Replay A                                      Replay B / P04 worker
--------                                      ---------------------
read owned Delivery
read delivery_status = #6 DEAD
read endpoint active
exists(active Attempt) = false
load original #6
pause before createReplay
                                              repeat eligibility reads
                                              INSERT Attempt #7 CREATED; COMMIT
                                              UPDATE #7 CREATED -> IN_FLIGHT,
                                                execution_generation 0 -> 1; COMMIT
                                              UPDATE #7 IN_FLIGHT -> SUCCEEDED
                                                WHERE generation = 1; COMMIT
resume
INSERT Attempt #7 CREATED; COMMIT
```

The durable result printed by the probe was:

```text
no=6 status=DEAD      generation=0 execution_claimed_at=null
no=7 status=SUCCEEDED generation=1 execution_claimed_at=null
no=7 status=CREATED   generation=0 execution_claimed_at=null
```

This is damaging in three ways: logical identity is duplicated, the latest-view result is ambiguous, and the stale A
row is separately executable even though B already succeeded.

### 6.3 Replay/automatic-retry control

The same A pause was used while B created replay 7, claimed it under P04, then called the real
`markFailedAndCreateRetry`. P04 atomically committed:

```text
#7 FAILED_RETRYING generation=1
#8 SCHEDULED       generation=0
```

When A resumed and attempted to insert stale `#7 CREATED`, PostgreSQL raised SQLState `23505` on
`idx_attempts_one_active_per_message_endpoint` because #8 was active. `DeliveryReplayService` translated it to the
existing conflict exception. The durable state contained one row each for 7 and 8.

This control proves the active guard protects that particular timing, not the sequence generally. If the active retry
also becomes terminal before A resumes, the same missing sequence constraint permits A's stale number; that longer
variant is an implementation regression case, not claimed as a separately observed probe result here.

### 6.4 Other creator pairs

- Replay/replay is reachable and defective as above.
- Replay/retry is reachable after a replay begins executing. While the retry child is active, the current index rejects
  stale replay; after terminal progression, only serialized allocation can protect eligibility and numbering.
- Retry/retry from the same execution is no longer a reachable allocator race after P04. Only one
  `(attempt id, IN_FLIGHT, positive generation)` completion updates the parent; zero-row stale completions create no
  child. Current P04 integration tests passed on this revision.
- Initial/append contention is unreachable because the initial Attempt belongs to a newly inserted Delivery inside the
  Message transaction.
- Recovery/append contention does not exist because recovery inserts no Attempt.

### 6.5 PostgreSQL 16 lock-order reproductions

The Endpoint writer/FK audit found that Endpoint deletion first takes the Endpoint delete row lock and then examines
referencing Subscription, Attempt, and Delivery rows. A deterministic two-connection PostgreSQL 16 probe reproduced
the original replay cycle:

```text
T1 replay:  Delivery FOR UPDATE
T2 delete:  DELETE Endpoint -> waits on T1's Delivery during FK examination
T1 replay:  Endpoint FOR SHARE -> SQLState 40P01 deadlock
```

A second probe reproduced an independent retry cycle even without an explicit Endpoint read:

```text
T1 retry:   Delivery FOR UPDATE
T2 delete:  DELETE Endpoint -> waits on T1's Delivery during FK examination
T1 retry:   INSERT Attempt -> implicit Endpoint FK KEY SHARE -> deadlock
```

PostgreSQL selected the deleting transaction as the second probe's victim, allowing retry to commit. Victim selection
can choose either participant and is not an allocation or API correctness mechanism.

The safe-order probes acquired Endpoint `FOR SHARE` for replay or `FOR KEY SHARE` for retry before Delivery
`FOR UPDATE`. Endpoint deletion then waited at the Endpoint row instead of forming a cycle. Replay's `FOR SHARE` also
ordered deactivation around replay commit. Retry's `FOR KEY SHARE` remained compatible with the non-key update used for
deactivation. Two replay transactions acquired compatible Endpoint `FOR SHARE` locks and independently locked two
different Deliveries belonging to that Endpoint, so the parent-first hierarchy does not serialize unrelated histories.

## 7. Required invariant

For every Delivery:

1. The committed Attempt numbers are exactly `1..N` for some `N >= 1`.
2. `(delivery_id, attempt_no)` uniquely identifies one physical Attempt.
3. Every post-initial allocator holds an exclusive PostgreSQL lock on that Delivery row from before reading allocation
   or replay eligibility state until its insert/decision commits.
4. The allocated number is the current committed maximum plus one, calculated after acquiring the lock.
5. Allocation and Attempt insertion occur in the same transaction. Rollback persists neither, so no gap is consumed.
6. Automatic retries and manual replays use the same sequence and lock protocol across all JVMs/instances.
7. Every operation appending an Attempt to an existing Delivery acquires parent locks in Endpoint-before-Delivery
   order. Replay uses Endpoint `FOR SHARE`; retry uses Endpoint `FOR KEY SHARE`. Both then use Delivery `FOR UPDATE`.
8. A successful replay observes the Endpoint active while holding `FOR SHARE`, retains that lock through Attempt
   commit, and linearizes at commit. Endpoint deactivation or deletion cannot commit between eligibility validation and
   replay commit.
9. Replay is accepted only if, at the serialized decision point, the latest Attempt is `DEAD`, the Endpoint is active,
   and no contradictory active Attempt exists.
10. Two replay requests are not assumed to be the same logical operation. The first may be accepted; the second is
   evaluated against state after the first. It succeeds with the next number only if the first replay has become
   `DEAD`; it is rejected if the current latest state is active or `SUCCEEDED`.
11. A newly inserted initial/replay Attempt is `CREATED`; a retry child is `SCHEDULED` with `next_retry_at`. All start
   with `execution_generation=0` and null `execution_claimed_at` and gain execution authority only through P04 claim.

`MAX(attempt_no) + 1` is acceptable only after locking the authoritative Delivery row and only when read plus insert
share the lock-holding transaction. It is not acceptable as an unlocked read, from a detached Attempt, or as a value
calculated in an earlier transaction.

## 8. Chosen design

### 8.1 Database serialization point and repository contract

Use the existing immutable Delivery row as the mutex for one sequence. The allocation repository exposes an explicit
ordered parent-lock contract rather than freely composable generic row-lock methods:

```java
public interface AttemptAllocationRepository {
    boolean lockReplayAllocationParentsIfEndpointActive(UUID endpointId, UUID deliveryId);
    void lockRetryAllocationParents(UUID endpointId, UUID deliveryId);
    int nextAttemptNoUnderDeliveryLock(UUID deliveryId);
}
```

`lockReplayAllocationParentsIfEndpointActive` executes Endpoint `FOR SHARE` first, returns `false` without taking the
Delivery lock when the row is present but inactive, and otherwise locks the matching Delivery `FOR UPDATE`.
`lockRetryAllocationParents` executes Endpoint `FOR KEY SHARE` before locking the matching Delivery `FOR UPDATE`.
Missing Endpoint or Delivery rows fail loudly. Both methods bind the Delivery to the supplied Endpoint when locking it.

All three operations require an already-active Spring transaction, enforced with mandatory transaction propagation;
they do not silently start an independent repository transaction. `nextAttemptNoUnderDeliveryLock` is deliberately
named and documented as valid only after an ordered parent-lock operation acquired that Delivery's allocation lock in
the same transaction. Application code cannot reliably prove ownership of a particular PostgreSQL tuple lock through a
stable Spring abstraction, so P05 does not inspect `pg_locks` at runtime. Correctness comes from structured repository
operations, mandatory transaction boundaries, deterministic PostgreSQL tests, and V13 uniqueness as the final
database backstop.

The Delivery lock is equivalent to:

```sql
SELECT id
FROM deliveries
WHERE id = :delivery_id
FOR UPDATE;
```

The lock is database-local, automatically released on commit/rollback, works across application instances, and has
one row per exact allocation scope. It needs no JVM synchronization or distributed lock service.

After locking, the same transaction reads:

```sql
SELECT COALESCE(MAX(attempt_no), 0) + 1
FROM attempts
WHERE delivery_id = :delivery_id;
```

and inserts that number before committing. V13 is the backstop if a future writer omits the protocol.

### 8.2 Replay transaction

`DeliveryReplayService` continues to authorize the immutable Delivery ownership path, but replay eligibility and
creation move into one proxied `@Transactional` method. That method:

1. locks the Endpoint row `FOR SHARE` and freshly reads `is_active`;
2. rejects without allocating or locking Delivery if the Endpoint is inactive;
3. locks the matching Delivery row `FOR UPDATE`;
4. reads the current highest-number Attempt from PostgreSQL;
5. rejects unless it is `DEAD`;
6. retains the active-row check as defense against pre-migration/inconsistent history;
7. calculates current max+1 and inserts one `CREATED`, generation-0 Attempt;
8. commits while still holding both parent locks, before `DeliveryReplayService` re-reads `delivery_status`.

The response read remains outside the allocation transaction. This preserves the current post-commit freshness
requirement without relying on `EntityManager.detach` of a preloaded immutable view row; the replay flow will no longer
load that view row before insertion.

The successful replay linearizes at commit. It observed active while holding Endpoint `FOR SHARE`, and that lock remains
held through commit, so deactivation or deletion cannot commit between validation and insertion. If replay obtains the
Endpoint lock first, replay may commit and deactivation proceeds afterward. If deactivation commits first, replay waits,
then observes inactive and rejects. If deactivation rolls back, replay may proceed after observing the still-active row.

### 8.3 Automatic retry transaction

`markFailedAndCreateRetry` will:

1. lock the Endpoint row `FOR KEY SHARE` without inspecting activity;
2. lock the parent Delivery row `FOR UPDATE`;
3. perform P04's existing conditional parent update using attempt ID, `IN_FLIGHT`, positive generation, and exact
   generation;
4. return `OWNERSHIP_LOST` without allocation if the update count is zero;
5. calculate current max+1 under the still-held Delivery lock;
6. insert one `SCHEDULED` generation-0 child with the existing `next_retry_at`;
7. commit parent and child atomically.

The retry Endpoint lock establishes safe ordering with deletion. It must remain `FOR KEY SHARE` unless a demonstrated
requirement justifies strengthening it; it is compatible with Endpoint's non-key activity update, and Endpoint activity
is not a retry eligibility condition. Replay never locks an Attempt row; it reads immutable/terminal state under the
allocation lock. P04 success/final-failure completions do not allocate and need neither parent lock. The retrying
completion takes both because it allocates.

### 8.4 Initial fan-out

Initial fan-out keeps fixed Attempt 1. Its Delivery is new and uncommitted in the same transaction; there can be no
competing allocator for its UUID. V13 verifies positivity/uniqueness, and the Message transaction continues to roll
back Message, Delivery, and Attempt together on failure.

## 9. Strategy comparison

| Strategy | Judgment |
|---|---|
| Endpoint-before-Delivery parent locks + Delivery `FOR UPDATE` + max+1 + unique constraint | **Chosen.** Endpoint `SHARE` makes replay eligibility linearizable through commit; Endpoint `KEY SHARE` orders retry against deletion without blocking deactivation; Delivery remains the exact allocation mutex across instances |
| Delivery lock plus non-locking Endpoint validation | Rejected. Deactivation can commit after the active read and before replay commit, and Attempt insertion still takes an implicit Endpoint FK `KEY SHARE`, so Endpoint deletion can reproduce the Delivery-to-Endpoint deadlock |
| Coordinate Endpoint mutations by locking every affected Delivery | Rejected. Fan-out grows with historical Delivery count, introduces ordering/phantom complexity, and is unnecessary when one Endpoint row provides the eligibility/deletion ordering point |
| Unique constraint + retry on conflict | Rejected alone. It prevents duplicate commit but does not make stale DEAD eligibility current. Retrying must revalidate product state, effectively recreating serialization with less predictable contention and error handling |
| Atomic counter on `deliveries` | Correct but unnecessary. `UPDATE ... RETURNING` would serialize and be gap-free when insert shares the transaction, but adds mutable duplicated state, backfill/drift audits, and mixed-version complexity without avoiding the replay eligibility transaction |
| Advisory lock keyed by Delivery UUID | Rejected. It creates a second, convention-only lock namespace with key derivation/collision/session semantics when a real parent row already exists |
| `SERIALIZABLE` transactions | Correct only with mandatory transaction retries and careful predicate coverage. Broader abort surface and operational complexity are unjustified for one-row scope |
| PostgreSQL/global sequence | Rejected. It gives global uniqueness, not contiguous per-Delivery order; rollback gaps are normal and replay eligibility remains unsolved |
| JVM `synchronized`/local keyed locks | Incorrect across multiple application instances and process restarts; database remains authoritative |

The chosen hierarchy has one global parent order and short critical sections. No allocator takes a second Delivery
lock. Endpoint `FOR SHARE` locks are mutually compatible, so replay allocation for distinct Deliveries sharing one
Endpoint remains concurrent. Endpoint update takes only the Endpoint row; Endpoint deletion takes Endpoint first and
then examines children through FKs. Message fan-out and Subscription creation do not lock an existing Delivery before
their Endpoint FK check. The production writer audit found no remaining Delivery-before-Endpoint path once retry and
replay both use the ordered repository operations. Lock-order review is an implementation acceptance item, not a
one-time prose assumption.

## 10. Replay/retry semantics

- Latest `IN_FLIGHT`, `CREATED`, or `SCHEDULED`: replay returns the existing 409 not-eligible contract and creates
  nothing.
- Retry is being created: replay and retry serialize on Delivery. Retry-first makes replay observe the new scheduled
  latest Attempt and reject. Replay-first is possible only from a current `DEAD`; after it inserts `CREATED`, any stale
  P04 completion for the older DEAD Attempt cannot update its parent or create a child.
- Latest `SUCCEEDED`: replay rejects. P05 does not add replay of successful history.
- Latest `DEAD`: replay may create exactly max+1 if the Endpoint is active.
- Replay obtains Endpoint `FOR SHARE` first: it may commit, then a waiting deactivation proceeds.
- Deactivation commits first: replay waits, observes inactive, rejects, and inserts nothing. If deactivation rolls back,
  replay may proceed after observing active.
- Replay versus Endpoint deletion must not deadlock. An Endpoint referenced by the replayable Delivery may cause the
  deletion to fail under the existing FK model after waiting; P05 does not redesign deletion semantics.
- Retry may overlap ordinary Endpoint deactivation because `FOR KEY SHARE` is compatible with the non-key activity
  update. Endpoint activity remains irrelevant to retry eligibility.
- Two concurrent replays: one evaluates first. The other evaluates the post-commit current history; it rejects while
  the winner is active or successful, and can create the next number only if the winner has already become DEAD.
- Replay failure at number 7 or above remains immediately DEAD; retry budget/policy is unchanged.
- Automatic retry and replay share one sequence but remain distinguishable operationally by their initiating path and
  lifecycle, not by a new schema discriminator.

Both `DeliveryNotDeadException` and `ActiveAttemptAlreadyExistsException` currently map to HTTP 409, and the exact
exception under a race is timing-dependent. P05 will make the normal locked decision use current latest status;
`ActiveAttemptAlreadyExistsException` remains a defensive inconsistent-history guard. HTTP status and accepted/rejected
product behavior remain unchanged, though the race's message becomes deterministic rather than index-timing-dependent.

## 11. API idempotency boundary

The replay endpoint has no idempotency key and remains non-idempotent. P05 answers whether each accepted operation gets
a safe distinct physical Attempt, not whether two client requests are duplicates of one logical command.

Two requests can therefore produce two Attempts only when each is independently eligible at its serialized point—for
example, replay 7 fails DEAD before replay 8 is evaluated. A client retry after losing the response can receive 409 if
the created replay is active/succeeded, or create the next Attempt if it has already become DEAD. Changing that contract
requires P08-style operation idempotency and is explicitly deferred.

## 12. P04 interaction

P04 and P05 remain separate authorities:

- P05 allocates a new Attempt identity under the Delivery lock.
- P04 grants/revokes execution authority for an existing Attempt by generation.

P05 does not set `IN_FLIGHT`, assign a positive generation, populate `execution_claimed_at`, bypass
`AttemptExecutionRepository.claim`, or change fenced completion predicates. New rows use database/JPA defaults:
generation 0, null claim time, and `CREATED` or `SCHEDULED`. Promotion changes only `SCHEDULED -> CREATED`; P04 claim
then increments generation.

V12's generation-0 migration sentinel does not conflict with P05. A historical migrated `IN_FLIGHT` generation-0 row
can be recovered and later claimed, but it cannot validly complete or create a retry before a positive-generation
claim. Reconciliation never allocates Attempt numbers, so it does not take the Delivery allocation lock.

## 13. Failure semantics

- Allocation transaction rollback leaves no Attempt and consumes no number.
- A V13 uniqueness violation after rollout is an invariant breach/future unclassified writer, not normal allocation
  arbitration. Log it at error with Delivery ID and creator type, increment a bounded counter, and propagate; do not
  blindly retry without revalidating state.
- A zero-row P04 parent update creates no retry child and consumes no number.
- Failure inserting a retry child rolls the fenced parent update back to `IN_FLIGHT`, preserving current recovery.
- A replay eligibility rejection performs no partial parent/Attempt mutation.
- Lock/deadlock/connection failures roll back and surface through existing unexpected-error handling. The client may
  retry; P05 does not make that retry idempotent.
- The unique constraint prevents an ambiguous durable view even if a future code path omits the lock.

## 14. Deterministic test strategy

All concurrency tests use latches/spies, separate threads/transactions, and committed PostgreSQL reads. No arbitrary
sleep is a correctness condition. Ordinary contexts retain P00's default suppression of schedules and Rabbit
listeners; only the existing full lifecycle test opts into real Rabbit behavior.

Required coverage:

1. Repository operations fail outside an active Spring transaction; missing Endpoint or Delivery rows fail safely.
2. Replay acquires Endpoint `FOR SHARE` before Delivery `FOR UPDATE`.
3. Retry acquires Endpoint `FOR KEY SHARE` before Delivery `FOR UPDATE`.
4. Replay first/deactivation second: deactivation is observably blocked until replay commits.
5. Deactivation first/replay second: replay waits, then observes inactive and rejects. A rolled-back deactivation lets
   replay proceed after observing active.
6. Replay/delete and retry/delete produce no SQLState `40P01`; current FK deletion behavior is asserted separately.
7. Retry's Endpoint `FOR KEY SHARE` remains compatible with Endpoint deactivation.
8. Two replays for different Deliveries sharing one Endpoint can both hold the Endpoint lock and reach their distinct
   Delivery locks concurrently.
9. Two concurrent replay requests stopped after both are ready: exactly one initial acceptance, current-state 409 for
   the loser, one next number.
10. Fast terminal success: delay A at the locked allocation boundary, allow B to create/claim/succeed, release A; A
   rejects based on `SUCCEEDED`, no duplicate and no new executable work.
11. Fast terminal DEAD: B create/claim/fail DEAD, then A allocates the next number, proving two independently valid
   requests can both succeed as 7 then 8.
12. Same-Delivery replay/retry: the Endpoint locks are compatible, Delivery serializes allocation, and replay observes
   the scheduled latest row after retry commits without partial mutation.
13. Retry/retry/P04: two completions for one generation create only one child; stale completion after child terminal
   creates none.
14. Two application threads/instances using separate transactions serialize on the Delivery row and receive distinct
   numbers when both operations are valid.
15. Inject failure after number calculation/before insert; rollback releases both parent locks, leaves max/count
   unchanged, and the next success reuses the unconsumed number.
16. Direct duplicate and non-positive inserts fail with SQLState `23505` and `23514` respectively.
17. V13 migration succeeds on valid V12 data and fails visibly on duplicate/non-contiguous/non-positive seeded data as
   specified by rollout audit policy.
18. New replay/retry rows have generation 0, null claim time, and correct `CREATED`/`SCHEDULED` lifecycle fields.
19. P04 claims a newly allocated row as generation 1 and completes it normally.
20. Message fan-out still creates exactly one Delivery/Attempt 1 per active subscription and remains atomic.
21. Delivery view latest selection/count/history remain correct and ordered after multiple retry/replay allocations.
22. Repository PostgreSQL audit executes every new native lock/max query.
23. Final source audit accounts for every constructor/save/native insert and fails review if an allocator bypasses the
    Delivery lock protocol.

Concurrency tests use deterministic transaction barriers plus observable PostgreSQL waits or `pg_blocking_pids`.
Arbitrary sleeps are not evidence of blocking, ordering, or absence of deadlock.

Regression selections include P00 background policy/cross-context tests; P01 byte/signing and P02 response-capture
worker tests; P03 destination/DNS/TLS tests; P04 fencing, ownership, atomic retry, migration, and reconciliation tests;
Delivery replay HTTP/lifecycle/service/concurrency tests; Delivery view tests; Message transaction tests; and the full
suite.

## 15. Migration and rollout

### 15.1 V13 contents

Recommended migration: `V13__enforce_attempt_delivery_sequence.sql`.

1. Abort with a descriptive exception if duplicate, non-positive, or non-contiguous histories exist.
2. Add `attempts_attempt_no_positive CHECK (attempt_no >= 1)`.
3. Add `uk_attempts_delivery_attempt_no UNIQUE (delivery_id, attempt_no)`.
4. The Task 1 checkpoint seeded representative history in an isolated PostgreSQL 16 schema and captured `EXPLAIN` for
   the actual descending latest-Attempt query with the old index absent. The plan used a backward scan of the unique
   Delivery/number index without a sort, so V13 removes `idx_attempts_delivery_attempt_no`.

The Task 1 evidence did not cover the mixed-order `delivery_status` list/count reads. The owner-authorized forward
correction is `V14__restore_attempt_delivery_ordering_index.sql`:

```sql
CREATE INDEX idx_attempts_delivery_attempt_no
    ON attempts (delivery_id, attempt_no DESC);
```

V13 remains immutable migration history. Apply V14 after V13 and retain both indexes: the unique ascending index
backs the correctness constraint; the mixed-order DESC index supports the existing read access pattern. The V12 ->
V13 -> V14 regression compares complete serialized Attempt rows before/after each migration, retains all diagnostics,
ordinals, statuses, execution metadata, and retry timestamps, and verifies both permanent SQLState backstops.

The positive check and unique constraint are permanent database guarantees. The contiguity audit is a migration-time
historical proof, not a declarative PostgreSQL constraint: PostgreSQL does not permanently enforce `1..N` gaplessness.
After migration, the Endpoint-before-Delivery hierarchy, Delivery serialization lock, max+1 calculation,
same-transaction insertion, and rollback semantics preserve contiguity.

### 15.2 Compatibility and deployment order

Schema-first is safe for data integrity with the current binary: ordinary current writes comply, and the historical
stale replay is rejected rather than committed if it races after V13. The old binary can still return the current
active-conflict 409 rather than the new deterministic current-status reason. It cannot claim full P05 semantics until
all instances use the locked allocator.

Recommended order:

1. run the read-only audit against the target database and export results;
2. ensure no duplicate/non-positive/non-contiguous rows; stop for explicit repair if any exist;
3. apply V13 plus V14 within an approved DDL lock window; budget the V14 non-concurrent index build's write blocking;
4. deploy the new allocator to all application instances;
5. run post-deploy audit and concurrent replay smoke test;
6. monitor allocation invariant violations and lock/deadlock/latency signals.

P04-style quiescing of workers is not required: V13 does not reinterpret execution authority or invalidate in-flight
worker capabilities. The DDL may wait for active writes and briefly blocks conflicting writes, so deployment must have
a lock-time budget, but background work need not be semantically drained.

Rollback of application code after V13 is structurally possible: old binaries can use the additive constraint, though
they lose P05 availability/current-eligibility guarantees under the stale race. Dropping V13 is not recommended because
it reopens durable corruption. Roll forward application failures. Database restore remains the recovery path for an
incorrect migration; no automatic duplicate repair is part of P05.

## 16. Observability

- Counter `relay.attempt.allocation{creator=initial|retry|replay,outcome=created|rejected|ownership_lost|invariant_violation}`
  with bounded tags only. Counters observe service decisions before transaction commit, not exact durable-row totals.
- Error log on V13 uniqueness/check violation after deployment with Delivery ID, creator, attempted number, and SQLState;
  never payload, response body, or signing secret.
- Debug/trace timing for Delivery-lock wait may be added through database metrics; do not put unbounded Delivery IDs in
  metric labels.
- Operational audit queries report duplicate/non-positive/non-contiguous histories and oldest allocation-lock waits.
- Existing P04 ownership-loss/revocation metrics remain unchanged and are not reused for P05 allocation outcomes.

## 17. Explicit exclusions

- no replay idempotency-key framework (P08 boundary);
- no replay-cycle cap or rate policy;
- no change to six-attempt retry schedule/finality;
- no new Delivery for replay;
- no replay of successful/non-DEAD history;
- no exactly-once receiver guarantee or receiver-side idempotency redesign;
- no scheduler leadership/routing work (P06);
- no P04 heartbeat/lease renewal or weakening of execution fencing;
- no API keys, entitlements, quotas, accounting, billing, or general Message idempotency;
- no generic distributed allocator, advisory-lock framework, or global sequence;
- no Endpoint deletion redesign and no P05-driven changes to `EndpointService`, `SubscriptionService`, or
  `MessageService`;
- no unrelated referential/schema normalization.

## 18. Owner decisions

No unresolved product decision blocks implementation. Current code plus approved Delivery/replay decisions establish
that retries and replays share one Delivery-scoped physical sequence, replay requires current `DEAD` plus active
Endpoint, replay 7+ retains current final-on-failure behavior, and the HTTP replay operation is non-idempotent.

The only rollout decision is operational: if the target audit finds historical duplicate/non-contiguous rows, an owner
must choose case-specific preservation/repair after reviewing receiver-effect evidence. P05 deliberately does not
pre-authorize deletion or renumbering of such rows.

## 19. Acceptance criteria

1. Fresh reproduction becomes a deterministic failing-first regression and passes only with locked current-state
   allocation.
2. Every production Attempt creator is positively inventoried and follows the rules above.
3. Database rejects duplicate delivery/number and non-positive numbers.
4. Every committed Delivery history is `1..N`; rollback consumes no number.
5. Replay cannot be authorized by an obsolete DEAD or endpoint-active snapshot.
6. Replay/retry and replay/replay behavior is deterministic across application instances.
7. P04 remains the sole execution authority and claims new generation-0 work normally.
8. P00–P04 relevant regressions and the full suite pass.
9. Migration audit/rollout/observability are documented and verified.
10. No P06/P08/tier/billing/exactly-once scope is introduced.
11. Replay and retry acquire Endpoint before Delivery, and deterministic PostgreSQL tests prove neither path deadlocks
    with Endpoint deletion.
12. Replay holds Endpoint `FOR SHARE` through commit; retry uses only Endpoint `FOR KEY SHARE` and remains compatible
    with ordinary Endpoint deactivation.
