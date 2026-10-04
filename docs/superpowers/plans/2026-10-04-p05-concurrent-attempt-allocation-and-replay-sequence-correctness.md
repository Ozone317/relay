# P05 Concurrent Attempt Allocation and Replay Sequence Correctness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every Delivery a positive, unique, contiguous committed Attempt sequence and make replay eligibility a
current, serialized PostgreSQL decision shared with automatic retry allocation.

**Architecture:** Use the immutable `deliveries` row as the per-history PostgreSQL sequence mutex, under a global
Endpoint-before-Delivery parent-lock hierarchy. Replay takes Endpoint `FOR SHARE`; retry takes Endpoint
`FOR KEY SHARE`; both then take Delivery `FOR UPDATE`, select max+1, and insert within one transaction. V13 permanently
enforces positive, unique `(delivery_id, attempt_no)`, while P04 remains the sole authority that claims and completes
an existing Attempt.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data JPA/JDBC, PostgreSQL 16, Flyway, JUnit 5, Mockito latches/spies,
Testcontainers, Micrometer

**Spec:** `docs/superpowers/specs/2026-10-04-p05-concurrent-attempt-allocation-and-replay-sequence-correctness-design.md`

## Global Constraints

- Do not create `IN_FLIGHT` Attempts or assign positive execution generations outside P04 claim SQL.
- Initial/replay rows start `CREATED`; retry rows start `SCHEDULED`; all new rows have generation 0 and null claim time.
- Automatic retry and replay share one Delivery-scoped sequence and one Delivery-row lock protocol.
- Every append to an existing Delivery acquires Endpoint before Delivery. Replay uses Endpoint `FOR SHARE`; retry uses
  Endpoint `FOR KEY SHARE` and must not make activity a retry eligibility condition.
- A successful replay holds Endpoint `FOR SHARE` from the active-state read through Attempt commit and linearizes at
  commit.
- Replay remains current-`DEAD` plus active-Endpoint only; replay 7+ remains final on failure.
- Replay HTTP remains non-idempotent and returns the existing 201/404/409/500 status contract.
- Allocation and insert share one transaction; rollback consumes no number.
- P00 background scheduling and Rabbit listeners remain suppressed unless a test explicitly opts in.
- Do not add P06 scheduler ownership, P08 idempotency, replay budgets, retry-policy changes, API keys, tiers, quotas,
  billing, exactly-once delivery, receiver idempotency, P04 renewal, or unrelated schema cleanup.
- Never auto-delete or renumber historical duplicate Attempts during migration.
- Allocation lock/number operations require an active Spring transaction. Do not introspect tuple-lock ownership
  through `pg_locks` in production code.
- Do not modify `EndpointService`, `SubscriptionService`, or `MessageService` to accommodate P05.

## File Structure

**Create:**

- `src/main/resources/db/migration/V13__enforce_attempt_delivery_sequence.sql` — audits V12 data and adds permanent
  positive and unique sequence constraints; drops the old ordering index only after Task 1 PostgreSQL 16 evidence.
- `src/main/java/com/example/relay/attempt/infrastructure/AttemptAllocationRepository.java` — ordered replay/retry
  parent-lock operations and explicitly preconditioned next-number contract.
- `src/main/java/com/example/relay/attempt/infrastructure/AttemptAllocationRepositoryImpl.java` — PostgreSQL native
  implementation using Endpoint `FOR SHARE`/`FOR KEY SHARE`, Delivery `FOR UPDATE`, mandatory transactions, and max+1.
- `src/main/java/com/example/relay/attempt/application/AttemptAllocationMetrics.java` — bounded allocation outcome
  counters.
- `src/test/java/com/example/relay/attempt/infrastructure/AttemptSequenceMigrationPostgresTest.java` — isolated V12→V13
  compatibility and constraint tests.
- `src/test/java/com/example/relay/attempt/infrastructure/AttemptAllocationRepositoryPostgresTest.java` — real lock,
  multi-connection, and next-number behavior.
- `src/test/java/com/example/relay/attempt/application/AttemptAllocationMetricsTest.java` — bounded creator/outcome tag
  coverage.

**Modify:**

- `src/main/java/com/example/relay/attempt/application/AttemptService.java` — common locked retry/replay allocation and
  metrics; retain P04 fencing.
- `src/main/java/com/example/relay/attempt/infrastructure/AttemptRepository.java` — current latest-Attempt query used
  only after the Delivery lock.
- `src/main/java/com/example/relay/delivery/application/DeliveryReplayService.java` — ownership lookup, delegated atomic
  replay creation, and fresh post-commit view read.
- `src/test/java/com/example/relay/delivery/application/DeliveryReplayConcurrencyPostgresTest.java` — deterministic
  simultaneous and fast-terminal races.
- `src/test/java/com/example/relay/delivery/application/DeliveryReplayServiceTest.java` — new collaboration and current
  exception behavior.
- `src/test/java/com/example/relay/delivery/application/DeliveryReplayLifecycleIntegrationTest.java` — shared sequence,
  generation-0 insertion, P04 claim, and repeated replay.
- `src/test/java/com/example/relay/delivery/application/DeliveryReplayHttpIntegrationTest.java` — fresh response after
  removing pre-insert `DeliveryStatus` load/detach.
- `src/test/java/com/example/relay/attempt/application/AttemptServiceTest.java` — locked replay/retry unit behavior and
  constructor dependencies.
- `src/test/java/com/example/relay/attempt/application/AttemptExecutionFencingIntegrationTest.java` — P04 retry/retry
  and terminal-child sequence regressions.
- `src/test/java/com/example/relay/attempt/application/AttemptServiceMarkFailedAndCreateRetryAtomicityTest.java` — lock,
  allocation, and rollback ordering.
- `src/test/java/com/example/relay/attempt/infrastructure/AttemptExecutionMigrationLifecyclePostgresTest.java` — supply
  the new allocation repository to its manually constructed `AttemptService` and preserve generation-0 recovery proof.
- `src/test/java/com/example/relay/deliveryengine/reconciliation/ReconciliationSweeperIntegrationTest.java` — supply the
  new constructor dependency to its delayed service fixture; prove reconciliation remains non-allocating.
- `src/test/java/com/example/relay/common/RepositoryPostgresAuditTest.java` — execute every new native allocation query.

## Review Focus

- A replay waiting behind a replay that succeeds must reject from current `SUCCEEDED`, not fall through to a stale
  insert; Task 3's fast-success test pins this.
- A replay waiting behind a replay that becomes `DEAD` is a second valid non-idempotent operation and must get number 8;
  Task 3's fast-DEAD test pins this.
- A replay/retry race must not deadlock or partially persist the P04 parent transition; Task 4's two-transaction test
  pins state and completion.
- Endpoint deletion must not close either reproduced allocation deadlock; Tasks 2–4 pin replay/delete and retry/delete
  with observable PostgreSQL waits and assert no SQLState `40P01`.
- An exception after allocation but before insert must release the lock and consume no number; Task 2 repository rollback
  and Task 4 service rollback tests pin this.
- Endpoint deactivation racing replay must have one row-lock-defined order through replay commit; Tasks 2–3 pin both
  orderings, while retry's weaker lock remains compatible with deactivation.

---

### Task 1: Enforce the database sequence contract

**Files:**

- Create: `src/main/resources/db/migration/V13__enforce_attempt_delivery_sequence.sql`
- Create: `src/test/java/com/example/relay/attempt/infrastructure/AttemptSequenceMigrationPostgresTest.java`

**Interfaces:**

- Consumes: Flyway V1–V12 schema and `SharedPostgresContainer.POSTGRES`.
- Produces: permanent constraints `attempts_attempt_no_positive` and `uk_attempts_delivery_attempt_no`; a recorded
  PostgreSQL 16 plan and evidence-gated decision to remove or retain `idx_attempts_delivery_attempt_no`.

- [ ] **Step 1: Write the failing V12→V13 migration tests**

Create isolated schemas like `AttemptExecutionMigrationLifecyclePostgresTest`. Add these exact test cases:

```java
@Test
void v13AcceptsContiguousHistoryAndEnforcesPositiveUniqueNumbers()

@Test
void v13RefusesDuplicateDeliveryAttemptNumber()

@Test
void v13RefusesNonContiguousHistory()

@Test
void v13RefusesNonPositiveAttemptNumber()
```

For each case, migrate the schema to target `12`, seed the minimum User→Environment→App→Event/Endpoint→Message→Delivery
chain with native SQL, then seed Attempts. The valid case uses terminal 1 and active 2, migrates to latest, and asserts:

```java
assertConstraintRejects(statement, duplicateInsertSql, "23505");
assertConstraintRejects(statement, zeroInsertSql, "23514");
assertTrue(constraintExists(statement, "uk_attempts_delivery_attempt_no"));
```

Invalid cases assert Flyway throws and its causal message contains `duplicate`, `non-contiguous`, or `non-positive`.
Always drop the isolated schema in `finally`.

- [ ] **Step 2: Run the tests and verify they fail because V13 is absent**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw \
  -Dtest=AttemptSequenceMigrationPostgresTest \
  -Drelay.retry.scheduling-enabled=false test
```

Expected: FAIL because latest remains V12 and the named constraints/audit behavior do not exist.

- [ ] **Step 3: Add V13 with explicit audit failures and constraints**

Use this migration shape, retaining descriptive exception text so operators know which read-only query to run:

```sql
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM attempts GROUP BY delivery_id, attempt_no HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'P05 migration blocked: duplicate (delivery_id, attempt_no); run the documented duplicate audit';
    END IF;

    IF EXISTS (SELECT 1 FROM attempts WHERE attempt_no < 1) THEN
        RAISE EXCEPTION 'P05 migration blocked: non-positive attempt_no';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM attempts
        GROUP BY delivery_id
        HAVING MIN(attempt_no) <> 1 OR MAX(attempt_no) <> COUNT(*)
    ) THEN
        RAISE EXCEPTION 'P05 migration blocked: non-contiguous Delivery attempt history';
    END IF;
END $$;

ALTER TABLE attempts
    ADD CONSTRAINT attempts_attempt_no_positive CHECK (attempt_no >= 1),
    ADD CONSTRAINT uk_attempts_delivery_attempt_no UNIQUE (delivery_id, attempt_no);
```

Do not repair invalid rows in the migration. Initially retain `idx_attempts_delivery_attempt_no`; its removal is a
separate evidence-gated decision below.

- [ ] **Step 4: Run migration and existing migration lifecycle tests**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw \
  -Dtest=AttemptSequenceMigrationPostgresTest,AttemptExecutionMigrationLifecyclePostgresTest \
  -Drelay.retry.scheduling-enabled=false test
```

Expected: PASS; valid V12 data upgrades, invalid histories fail before DDL, and P04 V12 behavior remains valid after
latest migration.

- [ ] **Step 5: Obtain PostgreSQL 16 plan evidence before deciding whether to remove the old index**

In the isolated valid-history schema, seed representative data with multiple Deliveries and enough Attempts per
Delivery to make an index plan meaningful. Analyze the tables. In that disposable schema only, drop
`idx_attempts_delivery_attempt_no`, use a real seeded Delivery UUID, and capture:

```sql
ANALYZE attempts;

EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT *
FROM attempts
WHERE delivery_id = '<seeded-delivery-id>'
ORDER BY attempt_no DESC
LIMIT 1;
```

Assert and record that the plan uses a backward scan of the index backing `uk_attempts_delivery_attempt_no`, with no
sort for the ordered lookup. Do not use an empty table, a fabricated UUID, or a plan collected while the old ordering
index still exists as removal evidence.

- [ ] **Step 6: Finalize the evidence-gated index decision and rerun migration tests**

If the representative plan clearly demonstrates the intended unique-index access path, add
`DROP INDEX idx_attempts_delivery_attempt_no` to V13 and assert the old index is absent after migration. If it does not,
leave V13 unchanged, assert that the old index remains, and record removal as optional cleanup outside P05. P05
correctness and Task 1 completion do not depend on removing the index.

**Task 1 resolution (2026-10-04):** In an isolated PostgreSQL 16.15 schema with 101 Deliveries and 2,002 Attempts,
the actual latest-Attempt query used `Index Scan Backward using uk_attempts_delivery_attempt_no` without a Sort after
the old index was dropped in that disposable schema. V13 removes the old index; the Task 1 report records the full plan.

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw \
  -Dtest=AttemptSequenceMigrationPostgresTest \
  -Drelay.retry.scheduling-enabled=false test
```

Expected: PASS with the index assertion matching the evidence-backed branch and the captured plan stored in test
output/evidence for the verification record.

- [ ] **Step 7: Check formatting and commit the schema checkpoint**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw spotless:check
git diff --check
```

Commit:

```bash
git add src/main/resources/db/migration/V13__enforce_attempt_delivery_sequence.sql \
  src/test/java/com/example/relay/attempt/infrastructure/AttemptSequenceMigrationPostgresTest.java
git commit -m "feat: enforce delivery attempt sequence constraints"
```

The checkpoint wording must state precisely: the check constraint permanently enforces positivity; the unique
constraint permanently enforces physical ordinal uniqueness; the preflight contiguity query proves only historical
cleanliness at migration time; PostgreSQL does not permanently enforce `1..N`; post-migration contiguity comes from the
Delivery lock, max+1 calculation, same-transaction insert, and rollback semantics.

### Task 2: Add the PostgreSQL allocation primitive

**Files:**

- Create: `src/main/java/com/example/relay/attempt/infrastructure/AttemptAllocationRepository.java`
- Create: `src/main/java/com/example/relay/attempt/infrastructure/AttemptAllocationRepositoryImpl.java`
- Create: `src/test/java/com/example/relay/attempt/infrastructure/AttemptAllocationRepositoryPostgresTest.java`
- Modify: `src/test/java/com/example/relay/common/RepositoryPostgresAuditTest.java`

**Interfaces:**

- Consumes: existing Delivery and Endpoint UUIDs and the caller's Spring transaction.
- Produces:

```java
public interface AttemptAllocationRepository {
    boolean lockReplayAllocationParentsIfEndpointActive(UUID endpointId, UUID deliveryId);
    void lockRetryAllocationParents(UUID endpointId, UUID deliveryId);
    int nextAttemptNoUnderDeliveryLock(UUID deliveryId);
}
```

The first method executes Endpoint `FOR SHARE` before Delivery `FOR UPDATE`, returning `false` without locking Delivery
only when the Endpoint exists but is inactive. The second executes Endpoint `FOR KEY SHARE` before Delivery
`FOR UPDATE`. Missing parents throw; all methods require an already-active transaction.

- [ ] **Step 1: Write failing real-PostgreSQL repository tests**

Add:

```java
@Test void nextAttemptNoUnderDeliveryLockReturnsOneForEmptyDeliveryAndMaxPlusOneForHistory()
@Test void allocationOperationsOutsideTransactionFailLoudly()
@Test void missingEndpointOrDeliveryFailsSafely()
@Test void replayLocksEndpointShareBeforeDeliveryUpdate()
@Test void retryLocksEndpointKeyShareBeforeDeliveryUpdate()
@Test void deliveryLockSerializesTwoTransactionsAcrossConnections()
@Test void rolledBackAllocationDoesNotConsumeNumber()
@Test void replayFirstBlocksDeactivateUntilReplayCommit()
@Test void deactivateFirstMakesReplayObserveInactiveAndSkipDeliveryLock()
@Test void replayAndEndpointDeleteDoNotDeadlock()
@Test void retryAndEndpointDeleteDoNotDeadlock()
@Test void retryEndpointKeyShareDoesNotBlockDeactivate()
@Test void replayLocksForDistinctDeliveriesOnOneEndpointRemainConcurrent()
```

Use two `TransactionTemplate`s backed by separate connections, `CountDownLatch` barriers, and observable PostgreSQL
blocking state via `pg_blocking_pids` or `pg_stat_activity`. Do not assert elapsed sleep time. For the original dangerous
interleavings, arrange deletion after the allocator holds Delivery and prove the new ordered operation cannot reach
that state; assert neither transaction reports SQLState `40P01`. In the rollback case, throw a sentinel exception after
reading the number, assert both parent locks are released, and assert the next transaction reads the same number.

- [ ] **Step 2: Run the repository test and verify missing-interface failure**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw \
  -Dtest=AttemptAllocationRepositoryPostgresTest \
  -Drelay.retry.scheduling-enabled=false test
```

Expected: test compilation fails because `AttemptAllocationRepository` is not defined.

- [ ] **Step 3: Implement the minimal JDBC repository**

Implement with `NamedParameterJdbcTemplate`. Mark each public operation with Spring transaction propagation
`MANDATORY` so invocation outside a caller-owned transaction fails with `IllegalTransactionStateException`; do not use
`REQUIRED`, which could silently create a short independent lock transaction.

The replay parent operation executes these statements in order:

```sql
SELECT is_active FROM endpoints WHERE id = :endpointId FOR SHARE;
-- Only when active:
SELECT id FROM deliveries
WHERE id = :deliveryId AND endpoint_id = :endpointId
FOR UPDATE;
```

The retry parent operation executes:

```sql
SELECT id FROM endpoints WHERE id = :endpointId FOR KEY SHARE;
SELECT id FROM deliveries
WHERE id = :deliveryId AND endpoint_id = :endpointId
FOR UPDATE;
```

The number operation executes:

```sql
SELECT COALESCE(MAX(attempt_no), 0) + 1 FROM attempts WHERE delivery_id = :deliveryId;
```

The parent operations must fail loudly if either row is missing or the Delivery does not belong to the supplied
Endpoint. `false` means only “Endpoint exists but is inactive.” Name and document
`nextAttemptNoUnderDeliveryLock` as safe only after one of these ordered operations locked that Delivery in the same
transaction.

Do not add runtime tuple-lock introspection. Spring does not expose a stable assertion that the current transaction owns
a particular PostgreSQL tuple lock. The enforceable boundary is mandatory transaction propagation plus structured
parent-lock methods, explicit naming/documentation, deterministic tests, and V13 uniqueness.

- [ ] **Step 4: Execute every native statement in the repository audit**

Extend `RepositoryPostgresAuditTest` to autowire `AttemptAllocationRepository`, execute both ordered parent operations
inside transactions against seeded Deliveries, and call `nextAttemptNoUnderDeliveryLock` only after the corresponding
Delivery lock. Assert replay active state and next number. This prevents native syntax and lock-mode drift from escaping
to production.

- [ ] **Step 5: Run repository tests**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw \
  -Dtest=AttemptAllocationRepositoryPostgresTest,RepositoryPostgresAuditTest \
  -Drelay.retry.scheduling-enabled=false test
```

Expected: PASS; no assertion depends on a wall-clock delay.

- [ ] **Step 6: Commit the allocation primitive**

```bash
git add src/main/java/com/example/relay/attempt/infrastructure/AttemptAllocationRepository.java \
  src/main/java/com/example/relay/attempt/infrastructure/AttemptAllocationRepositoryImpl.java \
  src/test/java/com/example/relay/attempt/infrastructure/AttemptAllocationRepositoryPostgresTest.java \
  src/test/java/com/example/relay/common/RepositoryPostgresAuditTest.java
git commit -m "feat: serialize attempt allocation by delivery"
```

### Task 3: Make replay eligibility and allocation one locked transaction

**Files:**

- Modify: `src/main/java/com/example/relay/attempt/application/AttemptService.java`
- Modify: `src/main/java/com/example/relay/attempt/infrastructure/AttemptRepository.java`
- Modify: `src/main/java/com/example/relay/delivery/application/DeliveryReplayService.java`
- Modify: `src/test/java/com/example/relay/attempt/application/AttemptServiceTest.java`
- Modify: `src/test/java/com/example/relay/delivery/application/DeliveryReplayServiceTest.java`
- Modify: `src/test/java/com/example/relay/delivery/application/DeliveryReplayConcurrencyPostgresTest.java`
- Modify: `src/test/java/com/example/relay/delivery/application/DeliveryReplayHttpIntegrationTest.java`

**Interfaces:**

- Consumes: `AttemptAllocationRepository` from Task 2; immutable authorized `Delivery` from
  `DeliveryRepository.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId`.
- Produces:

```java
Optional<Attempt> findFirstByDeliveryIdOrderByAttemptNoDesc(UUID deliveryId);

@Transactional
Attempt createReplay(Delivery delivery);
```

`createReplay` now accepts the Delivery, not a stale original Attempt.

- [ ] **Step 1: Replace the probabilistic replay concurrency test with deterministic failing-first cases**

Use a spy/latch around the ordered repository operation or a test-only transaction barrier, not a sleep. Add:

```java
@Test void twoConcurrentReplays_areSerializedAndOnlyCurrentEligibleRequestSucceeds()
@Test void delayedReplayAfterCompetingReplaySucceeds_isRejectedWithoutDuplicateOrExecutableWork()
@Test void delayedReplayAfterCompetingReplayBecomesDead_getsNextNumber()
@Test void replayFirstHoldsEndpointShareThroughCommitAndThenDeactivateProceeds()
@Test void deactivateFirstMakesReplayObserveInactiveAndReject()
@Test void replayAndEndpointDeleteDoNotDeadlock()
@Test void distinctDeliveriesOnSameEndpointRemainConcurrentAtEndpointLock()
```

The fast-success case reproduces the investigation order and expects one #7 `SUCCEEDED`, no second #7, and no new
`CREATED`. The fast-DEAD case expects #7 `DEAD` followed by #8 `CREATED`. Run the competing execution through real
`AttemptService.claim` and fenced completion so generation state is also asserted.

- [ ] **Step 2: Run the focused tests and verify the stale-snapshot failure**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw \
  -Dtest=DeliveryReplayConcurrencyPostgresTest \
  -Drelay.retry.scheduling-enabled=false test
```

Expected before refactor: fast-success fails with two #7 rows or the wrong index-timing exception; endpoint ordering is
not serialized, and the original Delivery-before-Endpoint replay path can deadlock against deletion.

- [ ] **Step 3: Add the latest-Attempt query and transactional replay allocation**

In `AttemptService.createReplay(Delivery)` perform this exact order:

```java
boolean parentsLocked = allocationRepository.lockReplayAllocationParentsIfEndpointActive(
        delivery.getEndpoint().getId(), delivery.getId());
if (!parentsLocked) {
    throw new ReplayEndpointInactiveException(delivery.getEndpoint().getId());
}
Attempt latest = attemptRepository.findFirstByDeliveryIdOrderByAttemptNoDesc(delivery.getId())
        .orElseThrow(() -> new DeliveryNotFoundException(delivery.getId()));
if (latest.getStatus() != AttemptStatus.DEAD) {
    throw new DeliveryNotDeadException(delivery.getId(), latest.getStatus());
}
if (attemptRepository.existsByMessageIdAndEndpointIdAndStatusIn(
        delivery.getMessage().getId(), delivery.getEndpoint().getId(), ACTIVE_STATUSES)) {
    throw new ActiveAttemptAlreadyExistsException(delivery.getMessage().getId(), delivery.getEndpoint().getId());
}
int attemptNo = allocationRepository.nextAttemptNoUnderDeliveryLock(delivery.getId());
return attemptRepository.saveAndFlush(new Attempt(delivery.getApp(), delivery.getMessage(),
        delivery.getEndpoint(), delivery, attemptNo));
```

Move the active-status constant to the service that owns the check. Do not catch V13 uniqueness violations as ordinary
concurrent replay; after locking, they are invariant failures. The Endpoint `FOR SHARE` and Delivery `FOR UPDATE` locks
remain held through the insert and transaction commit. A successful replay linearizes at commit.

- [ ] **Step 4: Simplify `DeliveryReplayService` around the new atomic boundary**

Keep the ownership-scoped Delivery lookup, call `attemptService.createReplay(delivery)`, then query
`deliveryStatusRepository.findById` after the transactional method returns. Remove pre-insert DeliveryStatus,
endpoint-active, active-Attempt, original-Attempt, `DataIntegrityViolationException`, and `EntityManager.detach` logic
and constructor dependencies. The post-commit view read remains required.

- [ ] **Step 5: Update unit tests for exact rejection ordering and fresh response**

`AttemptServiceTest` owns status/endpoint/active-history behavior. `DeliveryReplayServiceTest` verifies authorization,
one `createReplay(delivery)` call, and fresh status lookup after it returns. Preserve HTTP 409 mappings; concurrent loser
now deterministically reports current non-DEAD state instead of whichever check/index happened to win.

- [ ] **Step 6: Run replay tests**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw \
  -Dtest=AttemptServiceTest,DeliveryReplayServiceTest,DeliveryReplayConcurrencyPostgresTest,DeliveryReplayHttpIntegrationTest \
  -Drelay.retry.scheduling-enabled=false test
```

Expected: PASS; the HTTP response shows the committed new Attempt without `EntityManager.detach`.

- [ ] **Step 7: Commit atomic replay allocation**

```bash
git add src/main/java/com/example/relay/attempt/application/AttemptService.java \
  src/main/java/com/example/relay/attempt/infrastructure/AttemptRepository.java \
  src/main/java/com/example/relay/delivery/application/DeliveryReplayService.java \
  src/test/java/com/example/relay/attempt/application/AttemptServiceTest.java \
  src/test/java/com/example/relay/delivery/application/DeliveryReplayServiceTest.java \
  src/test/java/com/example/relay/delivery/application/DeliveryReplayConcurrencyPostgresTest.java \
  src/test/java/com/example/relay/delivery/application/DeliveryReplayHttpIntegrationTest.java
git commit -m "fix: allocate replay attempts from locked current state"
```

### Task 4: Put automatic retry on the same allocation protocol

**Files:**

- Modify: `src/main/java/com/example/relay/attempt/application/AttemptService.java`
- Modify: `src/test/java/com/example/relay/attempt/application/AttemptServiceTest.java`
- Modify: `src/test/java/com/example/relay/attempt/application/AttemptExecutionFencingIntegrationTest.java`
- Modify: `src/test/java/com/example/relay/attempt/application/AttemptServiceMarkFailedAndCreateRetryAtomicityTest.java`
- Modify: `src/test/java/com/example/relay/delivery/application/DeliveryReplayConcurrencyPostgresTest.java`
- Modify: `src/test/java/com/example/relay/attempt/infrastructure/AttemptExecutionMigrationLifecyclePostgresTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/reconciliation/ReconciliationSweeperIntegrationTest.java`

**Interfaces:**

- Consumes: P04 `AttemptExecution` and `AttemptAllocationRepository`.
- Produces: unchanged
  `AttemptMutationOutcome markFailedAndCreateRetry(AttemptExecution, Instant, Integer, String, String, Long)` contract,
  now Delivery-locked and current-max allocated.

- [ ] **Step 1: Write failing retry/replay and rollback tests**

Add/strengthen:

```java
@Test void replayWaitingBehindRetryCreation_observesScheduledLatestAndCreatesNothing()
@Test void twoCompletionsForOneGeneration_allocateOnlyOneChildNumber()
@Test void staleCompletionAfterFirstChildTerminates_allocatesNoReplacementNumber()
@Test void retryInsertFailure_rollsBackParentAndConsumesNoNumber()
@Test void retryLocksEndpointKeyShareBeforeDeliveryUpdate()
@Test void retryAndEndpointDeleteDoNotDeadlock()
@Test void retryEndpointKeyShareRemainsCompatibleWithDeactivate()
```

For the replay/retry case, let both paths acquire their compatible Endpoint locks, block replay at the Delivery lock,
let the current P04 owner commit `FAILED_RETRYING` plus `SCHEDULED`, release replay, and assert current-state 409 plus
one child. For retry/delete, use observable PostgreSQL blocking state and assert no SQLState `40P01`. The compatibility
case proves Endpoint `FOR KEY SHARE` does not block the non-key activity update. The rollback test injects the child-save
failure, asserts the parent is still `IN_FLIGHT`, then retries with the same valid generation and asserts the child uses
the previously unconsumed number.

- [ ] **Step 2: Run focused tests and verify current detached-parent allocation is exposed**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw \
  -Dtest=AttemptExecutionFencingIntegrationTest,AttemptServiceMarkFailedAndCreateRetryAtomicityTest,DeliveryReplayConcurrencyPostgresTest \
  -Drelay.retry.scheduling-enabled=false test
```

Expected before implementation: at least the lock/current-max assertions fail.

- [ ] **Step 3: Lock before the P04 parent update and allocate current max+1**

Change `markFailedAndCreateRetry` in this order:

```java
Attempt previous = execution.attempt();
allocationRepository.lockRetryAllocationParents(
        previous.getEndpoint().getId(), previous.getDelivery().getId());
int updated = executionRepository.markFailed(execution, AttemptStatus.FAILED_RETRYING, nextRetryAt,
        responseCode, truncate(responseBody, DIAGNOSTIC_CHARACTER_LIMIT),
        truncate(lastError, DIAGNOSTIC_CHARACTER_LIMIT), latencyMs);
if (updated == 0) return AttemptMutationOutcome.OWNERSHIP_LOST;
if (updated != 1) throw new IllegalStateException("unexpected parent update count: " + updated);
int nextAttemptNo = allocationRepository.nextAttemptNoUnderDeliveryLock(previous.getDelivery().getId());
Attempt retry = new Attempt(previous.getApp(), previous.getMessage(), previous.getEndpoint(),
        previous.getDelivery(), nextAttemptNo);
retry.setStatus(AttemptStatus.SCHEDULED);
retry.setNextRetryAt(nextRetryAt);
attemptRepository.save(retry);
return AttemptMutationOutcome.APPLIED;
```

Keep all operations in the existing proxied transaction. The Endpoint lock is `FOR KEY SHARE` only: it orders retry
against deletion, remains compatible with Endpoint deactivation, and does not inspect `is_active`. Do not strengthen it
without a demonstrated requirement. Remove or privatize the old public `createRetry(Attempt, Instant)` so no production
caller can allocate from a detached parent's number without the ordered parent locks.

- [ ] **Step 4: Update manual constructor fixtures**

Supply a real `AttemptAllocationRepositoryImpl` (or an explicit mock where the test is strictly isolated) to manually
constructed `AttemptService` instances in `AttemptExecutionMigrationLifecyclePostgresTest` and
`ReconciliationSweeperIntegrationTest`. Do not weaken either P04 generation-0 or delayed-sweep assertions.

- [ ] **Step 5: Run P04 plus replay/retry tests**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw \
  -Dtest=AttemptServiceTest,AttemptExecutionFencingIntegrationTest,AttemptServiceMarkFailedAndCreateRetryAtomicityTest,DeliveryReplayConcurrencyPostgresTest,AttemptExecutionMigrationLifecyclePostgresTest,ReconciliationSweeperIntegrationTest \
  -Drelay.retry.scheduling-enabled=false test
```

Expected: PASS; retry allocation remains atomic and every stale generation creates zero children.

- [ ] **Step 6: Commit shared retry allocation**

```bash
git add src/main/java/com/example/relay/attempt/application/AttemptService.java \
  src/test/java/com/example/relay/attempt/application/AttemptServiceTest.java \
  src/test/java/com/example/relay/attempt/application/AttemptExecutionFencingIntegrationTest.java \
  src/test/java/com/example/relay/attempt/application/AttemptServiceMarkFailedAndCreateRetryAtomicityTest.java \
  src/test/java/com/example/relay/delivery/application/DeliveryReplayConcurrencyPostgresTest.java \
  src/test/java/com/example/relay/attempt/infrastructure/AttemptExecutionMigrationLifecyclePostgresTest.java \
  src/test/java/com/example/relay/deliveryengine/reconciliation/ReconciliationSweeperIntegrationTest.java
git commit -m "fix: share delivery sequence allocation with retries"
```

### Task 5: Preserve lifecycle behavior and add bounded allocation observability

**Files:**

- Create: `src/main/java/com/example/relay/attempt/application/AttemptAllocationMetrics.java`
- Create: `src/test/java/com/example/relay/attempt/application/AttemptAllocationMetricsTest.java`
- Modify: `src/main/java/com/example/relay/attempt/application/AttemptService.java`
- Modify: `src/test/java/com/example/relay/delivery/application/DeliveryReplayLifecycleIntegrationTest.java`
- Modify: `src/test/java/com/example/relay/delivery/infrastructure/DeliveryStatusViewPostgresTest.java`
- Modify: `src/test/java/com/example/relay/message/application/MessageServiceTransactionIntegrationTest.java`

**Interfaces:**

- Consumes: Micrometer `MeterRegistry` and allocation outcomes from Tasks 3–4.
- Produces:

```java
void record(String creator, String outcome);
```

with metric `relay.attempt.allocation` and bounded values:
`creator=initial|retry|replay`,
`outcome=created|rejected|ownership_lost|invariant_violation`.

- [ ] **Step 1: Write failing metrics and lifecycle tests**

Assert:

```java
assertCounter("replay", "created", 1.0);
assertCounter("replay", "rejected", 1.0);
assertCounter("retry", "ownership_lost", 1.0);
assertCounter("retry", "created", 1.0);
```

Extend lifecycle assertions so replay/retry rows are generation 0 with null claim time, P04 claim changes only the
selected row to generation 1/`IN_FLIGHT`, repeated DEAD replay produces consecutive numbers, and
`attemptCount == latestAttemptNo` for contiguous seeded histories. Message fan-out still creates Attempt 1 atomically.

- [ ] **Step 2: Run the focused tests and verify missing metrics/lifecycle assertions fail**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw \
  -Dtest=AttemptAllocationMetricsTest,DeliveryReplayLifecycleIntegrationTest,DeliveryStatusViewPostgresTest,MessageServiceTransactionIntegrationTest \
  -Drelay.retry.scheduling-enabled=false test
```

Expected: metrics class is absent or new counter assertions fail.

- [ ] **Step 3: Implement bounded counters and invariant-violation logging**

Follow `ExecutionOwnershipMetrics`: use only the fixed `creator` and `outcome` tags. Record success/rejection/ownership
loss at the service decision. Catch `DataIntegrityViolationException` only to record/log
`invariant_violation` with Delivery ID, creator, attempted number, and most-specific SQLState, then rethrow the original
exception. Do not translate it into a normal replay conflict and do not attach payload, endpoint URL, or unbounded IDs
to metric tags.

- [ ] **Step 4: Run lifecycle and metrics tests**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw \
  -Dtest=AttemptAllocationMetricsTest,DeliveryReplayLifecycleIntegrationTest,DeliveryStatusViewPostgresTest,MessageServiceTransactionIntegrationTest \
  -Drelay.retry.scheduling-enabled=false test
```

Expected: PASS, including real P04 claim of new work.

- [ ] **Step 5: Commit lifecycle and observability coverage**

```bash
git add src/main/java/com/example/relay/attempt/application/AttemptAllocationMetrics.java \
  src/main/java/com/example/relay/attempt/application/AttemptService.java \
  src/test/java/com/example/relay/attempt/application/AttemptAllocationMetricsTest.java \
  src/test/java/com/example/relay/delivery/application/DeliveryReplayLifecycleIntegrationTest.java \
  src/test/java/com/example/relay/delivery/infrastructure/DeliveryStatusViewPostgresTest.java \
  src/test/java/com/example/relay/message/application/MessageServiceTransactionIntegrationTest.java
git commit -m "test: verify allocated attempt lifecycle and metrics"
```

### Task 6: Final creator and lock-order audit, regression gate, and rollout record

**Files:**

- Modify if implementation facts changed: `docs/superpowers/specs/2026-10-04-p05-concurrent-attempt-allocation-and-replay-sequence-correctness-design.md`
- Create: `docs/reviews/2026-10-04-p05-verification.md`

**Interfaces:**

- Consumes: all prior tasks and current source tree.
- Produces: positive production-writer inventory, exact command evidence, migration audit SQL/output template, and rollout
  checklist; no production behavior.

- [ ] **Step 1: Perform the positive Attempt-creator/allocation audit**

Run:

```bash
rg -n --hidden --glob '!target/**' --glob '!.git/**' --glob '!.worktrees/**' \
  'new Attempt\(|AttemptRepository\.save|attemptRepository\.(save|saveAll|saveAndFlush)|INSERT INTO attempts|attempt_no|nextAttemptNo' \
  src/main/java src/main/resources/db/migration
```

Record every result in the verification document as one of:

```text
initial fan-out: new Delivery, fixed Attempt 1, same Message transaction
automatic retry: Endpoint KEY SHARE -> Delivery UPDATE -> P04 parent fence -> current max+1 SCHEDULED insert
manual replay: Endpoint SHARE -> active -> Delivery UPDATE -> current eligibility -> current max+1 CREATED insert
existing-row mutation only: P04/reconciliation/dispatcher/dead-letter
schema only: Flyway DDL/audit
```

Any fourth production creator blocks completion until it is moved onto the same protocol and tested.

Repeat the positive Endpoint-writer/FK audit as an implementation acceptance item. Record every production Endpoint
create/update/deactivate/delete writer, its transaction boundary, mutation SQL/JPA mechanism, implicit row lock, FK
checks, and whether it can touch a Delivery in the same transaction. Also audit Delivery/Attempt insert and mutation
paths for any Delivery-before-Endpoint acquisition. Any new reverse edge blocks completion until the hierarchy is
restored and covered by a deterministic PostgreSQL regression.

- [ ] **Step 2: Run the migration preflight query against the test schema and save its zero-row result**

Use the exact duplicate and contiguity SQL from the spec. Document that target deployment databases must run the same
read-only audit and that non-empty output blocks V13; do not claim external production data was checked from this
repository session.

- [ ] **Step 3: Run focused P00–P04 and P05 regressions**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw \
  -Dtest=BackgroundExecutionContextPolicyTest,BackgroundExecutionCrossContextRegressionTest,\
ApacheResponseConsumptionIntegrationTest,WebhookDestinationAdversarialIntegrationTest,\
AttemptExecutionFencingIntegrationTest,DeliveryWorkerOwnershipFencingIntegrationTest,\
AttemptServiceMarkFailedAndCreateRetryAtomicityTest,AttemptSequenceMigrationPostgresTest,\
AttemptAllocationRepositoryPostgresTest,DeliveryReplayConcurrencyPostgresTest,\
DeliveryReplayLifecycleIntegrationTest,DeliveryReplayHttpIntegrationTest \
  -Drelay.retry.scheduling-enabled=false test
```

Expected: PASS. If an explicitly Rabbit-enabled class is added to this selection, retain its existing P00 opt-in
annotation/configuration rather than globally enabling listeners.

- [ ] **Step 4: Run format and the complete Docker-backed suite**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw spotless:check
make test-full-docker
git diff --check
```

Expected: all commands PASS. Record test counts and any environment-only skip explicitly.

- [ ] **Step 5: Confirm schema/index shape and the Task 1 query-plan decision**

Against PostgreSQL 16, record:

```sql
SELECT conname, pg_get_constraintdef(oid)
FROM pg_constraint
WHERE conrelid = 'attempts'::regclass
  AND conname IN ('attempts_attempt_no_positive', 'uk_attempts_delivery_attempt_no');

SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = current_schema()
  AND tablename = 'attempts'
  AND indexname IN ('idx_attempts_delivery_attempt_no', 'uk_attempts_delivery_attempt_no');
```

Expected: both permanent constraints exist. The presence or absence of the old ordering index exactly matches Task 1's
recorded representative PostgreSQL 16 evidence. If removed, attach the Task 1 plan showing the unique index's backward
scan; do not make Task 6 the first evidence collected after a destructive migration. If retained, record that removal
is optional cleanup and not required for P05 correctness.

- [ ] **Step 6: Self-review against the design**

Verify explicitly:

- every spec invariant maps to a passing test;
- replay acquires Endpoint `FOR SHARE`, reads activity, and only then acquires Delivery `FOR UPDATE`; no current-history
  check occurs before the Delivery lock and later authorizes an insert;
- retry acquires Endpoint `FOR KEY SHARE`, then Delivery `FOR UPDATE`, before the P04 parent update;
- replay/delete and retry/delete regressions report no `40P01`, while retry/deactivation remains compatible;
- allocation repository operations fail outside an active transaction, and
  `nextAttemptNoUnderDeliveryLock` is never called without the same transaction's ordered parent lock;
- no new row is `IN_FLIGHT` or positive-generation at insert;
- no catch silently converts V13 invariant failure into normal concurrency;
- no autonomous background component was enabled accidentally;
- no P06/P08/tier/billing code entered the diff;
- mixed-version/schema-first and non-quiesced rollout statements still match implementation.
- `EndpointService`, `SubscriptionService`, and `MessageService` were not changed for P05.

- [ ] **Step 7: Commit the verification checkpoint**

```bash
git add docs/superpowers/specs/2026-10-04-p05-concurrent-attempt-allocation-and-replay-sequence-correctness-design.md \
  docs/reviews/2026-10-04-p05-verification.md
git commit -m "docs: record P05 allocation verification and rollout"
```

Stop after this checkpoint and request deployment/merge approval. Do not begin P06, P08, or paid-tier work.
