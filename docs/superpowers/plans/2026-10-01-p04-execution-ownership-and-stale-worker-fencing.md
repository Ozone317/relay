# P04 — Execution Ownership and Stale-Worker Fencing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure an Attempt execution that loses ownership cannot mutate authoritative lifecycle state, create retry work, or publish terminal notification work.

**Architecture:** Add a PostgreSQL-backed monotonic `execution_generation` acquired by every `CREATED -> IN_FLIGHT` claim. Replace detached-entity completion merges with conditional updates requiring `id + IN_FLIGHT + matching positive generation`; keep retry-child creation in the same transaction after the fenced parent update. Reconciliation revokes the observed generation using a dedicated database-stamped claim time, and stale/duplicate Rabbit deliveries finish normally without another HTTP request or redelivery loop.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Data JPA/JDBC, PostgreSQL 16, Flyway, Spring AMQP 3.2, Micrometer/Actuator, JUnit 5, Mockito, Testcontainers PostgreSQL/RabbitMQ.

**Spec:** `docs/superpowers/specs/2026-10-01-p04-execution-ownership-and-stale-worker-fencing-design.md`

## Global Constraints

- Do not implement exactly-once webhook delivery, receiver-side idempotency, P05 replay allocation, or P06 scheduler architecture.
- Preserve the six-attempt lifecycle, retry delays/jitter, Attempt/Delivery identities, and manual-replay behavior.
- Preserve P01's exact signed/transmitted byte array and headers/signature format.
- Preserve P02's 10,241-byte maximum read, 10,240-byte retention, and abort-not-drain behavior.
- Preserve P03's destination validation, per-dial policy enforcement, TLS identity, and monotonic 15-second exchange deadline.
- Keep `relay.reconciliation.in-flight-grace=90s`; this project does not silently tune timeout policy.
- PostgreSQL is the time authority for execution claim age and recovery predicates.
- Ownership loss is a normal supersession outcome from Rabbit's perspective: no state overwrite, successor insert, dead-letter publish, nack, or requeue.
- Unexpected programming/persistence failures by the active owner remain exceptional and visible.
- Mixed unfenced/fenced background writers are unsupported; deployment must quiesce old workers before V12.
- Concurrency correctness tests use latches/barriers, controlled transport, and committed database state, never arbitrary sleeps.

## Review Focus

- A row missing after a zero-row completion must remain an ownership-loss diagnostic, not become authority to retry; Task 4 tests the absent/current-state logging path without a retry.
- Generation overflow must fail the claim transaction rather than wrap negative; Task 1 tests `Long.MAX_VALUE` and the non-negative constraint.
- A V12-migrated `IN_FLIGHT` row has recovery-only generation 0: Task 1 proves generation 0 cannot complete, reconciliation can reset it after grace, and the next claim at generation 1 can complete.
- A delayed reconciliation candidate must not revoke a newer generation even when its old timestamp argument is stale; Task 5 tests the observed-generation predicate.
- An active owner's child-insert failure must roll back the already-executed JDBC parent update; Task 3 retains and adapts the atomicity spy/integration test.
- Dead-letter publication must happen only after an applied fenced `DEAD` commit; Task 4 proves committed visibility from an independent transaction inside the publisher callback, with the queue/notifier isolated.

---

## Planned file structure

**Create:**

- `src/main/resources/db/migration/V12__add_attempt_execution_fencing.sql` — additive columns, backfill, constraints, and stale-claim index.
- `src/main/java/com/example/relay/attempt/application/AttemptExecution.java` — immutable worker capability: Attempt snapshot, generation, claim time.
- `src/main/java/com/example/relay/attempt/application/AttemptMutationOutcome.java` — `APPLIED` versus `OWNERSHIP_LOST`.
- `src/main/java/com/example/relay/attempt/infrastructure/AttemptExecutionClaim.java` — JDBC claim result.
- `src/main/java/com/example/relay/attempt/infrastructure/AttemptExecutionCandidate.java` — stale candidate with its observed generation and claim time.
- `src/main/java/com/example/relay/attempt/infrastructure/AttemptExecutionRepository.java` — focused claim/completion/reset contract.
- `src/main/java/com/example/relay/attempt/infrastructure/AttemptExecutionRepositoryImpl.java` — exact PostgreSQL conditional mutations.
- `src/main/java/com/example/relay/deliveryengine/worker/ExecutionOwnershipMetrics.java` — bounded-cardinality counters.
- `src/test/java/com/example/relay/attempt/application/AttemptExecutionFencingIntegrationTest.java` — committed-state A/B/gap/ABA/failure fencing.
- `src/test/java/com/example/relay/attempt/infrastructure/AttemptExecutionMigrationLifecyclePostgresTest.java` — pre-V12 `IN_FLIGHT` migration, generation-0 recovery, and generation-1 completion.
- `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerOwnershipFencingIntegrationTest.java` — controlled transport plus real Rabbit acknowledgment behavior.

**Modify:**

- `src/main/java/com/example/relay/attempt/domain/Attempt.java` — map generation and claim timestamp read-only to ordinary entity mutation.
- `src/main/java/com/example/relay/attempt/infrastructure/AttemptRepository.java` — remove old native claim/reset and `updatedAt` stale-execution finder methods.
- `src/main/java/com/example/relay/attempt/application/AttemptService.java` — claim capability and fenced completion transaction orchestration.
- `src/main/java/com/example/relay/deliveryengine/worker/DeliveryWorker.java` — carry capability, classify ownership loss, and gate dead-letter publication.
- `src/main/java/com/example/relay/deliveryengine/reconciliation/ReconciliationSweeper.java` — generation-scoped revocation and metrics.
- Existing Attempt, dispatcher, reconciliation, worker, replay, and repository tests listed in the tasks below.

## Interface lock

Use these names/signatures consistently across all tasks:

```java
public record AttemptExecution(Attempt attempt, long generation, Instant claimedAt) {}

public enum AttemptMutationOutcome {
    APPLIED,
    OWNERSHIP_LOST
}

public record AttemptExecutionClaim(long generation, Instant claimedAt) {}

public record AttemptExecutionCandidate(UUID attemptId, long generation, Instant claimedAt) {}

public interface AttemptExecutionRepository {
    Optional<AttemptExecutionClaim> claim(UUID attemptId);
    List<AttemptExecutionCandidate> findStaleInFlight(Duration grace, int limit);
    int markSucceeded(UUID attemptId, long generation, Integer responseCode,
            String responseBody, Long latencyMs);
    int markFailed(UUID attemptId, long generation, AttemptStatus status,
            Instant nextRetryAt, Integer responseCode, String responseBody,
            String lastError, Long latencyMs);
    int resetStuck(UUID attemptId, long observedGeneration, Duration grace);
}
```

`AttemptService` exposes:

```java
Optional<AttemptExecution> claim(UUID attemptId);
AttemptMutationOutcome markSucceeded(AttemptExecution execution, Integer responseCode,
        String responseBody, Long latencyMs);
AttemptMutationOutcome markFailed(AttemptExecution execution, AttemptStatus status,
        Instant nextRetryAt, Integer responseCode, String responseBody,
        String lastError, Long latencyMs);
AttemptMutationOutcome markFailedAndCreateRetry(AttemptExecution execution,
        Instant nextRetryAt, Integer responseCode, String responseBody,
        String lastError, Long latencyMs);
int resetStuck(UUID attemptId, long observedGeneration, Duration grace);
```

Do not retain unfenced overloads accepting a bare `Attempt`; compile failures are intentional migration evidence.

---

### Task 1: Add the execution epoch schema and PostgreSQL ownership repository

**Files:**

- Create: `src/main/resources/db/migration/V12__add_attempt_execution_fencing.sql`
- Create: `src/main/java/com/example/relay/attempt/application/AttemptExecution.java`
- Create: `src/main/java/com/example/relay/attempt/application/AttemptMutationOutcome.java`
- Create: `src/main/java/com/example/relay/attempt/infrastructure/AttemptExecutionClaim.java`
- Create: `src/main/java/com/example/relay/attempt/infrastructure/AttemptExecutionCandidate.java`
- Create: `src/main/java/com/example/relay/attempt/infrastructure/AttemptExecutionRepository.java`
- Create: `src/main/java/com/example/relay/attempt/infrastructure/AttemptExecutionRepositoryImpl.java`
- Modify: `src/main/java/com/example/relay/attempt/domain/Attempt.java`
- Modify: `src/main/java/com/example/relay/attempt/infrastructure/AttemptRepository.java`
- Modify: `src/test/java/com/example/relay/attempt/infrastructure/AttemptRepositoryTest.java`
- Modify: `src/test/java/com/example/relay/common/RepositoryPostgresAuditTest.java`
- Create: `src/test/java/com/example/relay/attempt/infrastructure/AttemptExecutionRepositoryPostgresTest.java`
- Create: `src/test/java/com/example/relay/attempt/infrastructure/AttemptExecutionMigrationLifecyclePostgresTest.java`

**Consumes:** Existing Attempt schema, `NamedParameterJdbcTemplate`, and the current `CREATED`/`IN_FLIGHT` state model.

**Produces:** The locked interfaces above and mapped `Attempt.getExecutionGeneration()` / `getExecutionClaimedAt()`.

**Invariant established:** Every successful claim acquires a database-stamped, strictly increasing generation; reset/completion primitives can compare that generation.

- [ ] **Step 1: Write migration and mapping tests first**

Add tests that start from migrated PostgreSQL and assert:

```java
assertEquals(0L, created.getExecutionGeneration());
assertNull(created.getExecutionClaimedAt());

AttemptExecutionClaim first = executionRepository.claim(created.getId()).orElseThrow();
assertEquals(1L, first.generation());
assertNotNull(first.claimedAt());
```

In a dedicated PostgreSQL schema, programmatically migrate through V11, insert the required foreign-key fixtures and a
pre-V12 Attempt with `status='IN_FLIGHT'` and an old `updated_at`, then run V12. Assert V12 gives that row generation 0
and backfills `execution_claimed_at` from `updated_at`. Assert the constraints reject a negative generation,
`IN_FLIGHT` with a null claim time, and a non-`IN_FLIGHT` status with a non-null claim time. These tests lock the
bidirectional consistency constraint down as an intentional detector for any future SQL/JPA bypass.

- [ ] **Step 2: Run failing-first evidence**

```bash
./mvnw test -Dtest=AttemptExecutionRepositoryPostgresTest,AttemptExecutionMigrationLifecyclePostgresTest,AttemptRepositoryTest,RepositoryPostgresAuditTest
```

Expected: test compilation fails because the V12 fields/types/repository do not exist.

- [ ] **Step 3: Add V12 exactly as specified**

```sql
ALTER TABLE attempts
    ADD COLUMN execution_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN execution_claimed_at TIMESTAMPTZ;

UPDATE attempts SET execution_claimed_at = updated_at WHERE status = 'IN_FLIGHT';

ALTER TABLE attempts
    ADD CONSTRAINT attempts_execution_generation_nonnegative CHECK (execution_generation >= 0),
    ADD CONSTRAINT attempts_execution_claim_consistency
        CHECK ((status = 'IN_FLIGHT') = (execution_claimed_at IS NOT NULL));

CREATE INDEX idx_attempts_stale_in_flight
    ON attempts (execution_claimed_at, id) WHERE status = 'IN_FLIGHT';
```

- [ ] **Step 4: Implement claim, stale selection, and reset SQL**

Claim uses `CURRENT_TIMESTAMP`, increments in-place, and returns generation/time. Reset uses:

```sql
WHERE id = :attemptId
  AND status = 'IN_FLIGHT'
  AND execution_generation = :observedGeneration
  AND execution_claimed_at < CURRENT_TIMESTAMP - (:graceMillis * INTERVAL '1 millisecond')
```

and clears execution/ready-publication timestamps while returning to `CREATED`. Bind `grace.toMillis()`; do not build
an interval string. For a row at `Long.MAX_VALUE`, PostgreSQL arithmetic must fail and leave it unchanged.

`findStaleInFlight` compares `execution_claimed_at` with PostgreSQL `CURRENT_TIMESTAMP - grace`, orders by
`execution_claimed_at, id`, limits the batch, and returns ID/generation/claim time. It performs no mutation; reset
rechecks all authority predicates.

- [ ] **Step 5: Add exact success/failure conditional updates to the repository**

Both statements require `id + status='IN_FLIGHT' + matching execution_generation + execution_generation > 0`.
Generation 0 is the V12 migration sentinel and is never a new-protocol completion capability. The statements set all
lifecycle diagnostics explicitly, truncate only in `AttemptService`, clear `execution_claimed_at`, and use
`CURRENT_TIMESTAMP`. Reject any status other than `FAILED_RETRYING` or `DEAD` in `markFailed` before executing SQL.

- [ ] **Step 6: Prove the complete migrated-`IN_FLIGHT` lifecycle**

In `AttemptExecutionMigrationLifecyclePostgresTest`, continue from the V11-to-V12 fixture and prove, in committed
PostgreSQL state:

1. the migrated row is `IN_FLIGHT`, generation 0, with its claim timestamp backfilled from `updated_at`;
2. fenced success supplied generation 0 affects zero rows, and `markFailedAndCreateRetry` supplied a generation-0
   `AttemptExecution` returns `OWNERSHIP_LOST`, leaves the parent unchanged, and inserts no child;
3. after making the configured grace condition true, the real generation-scoped reset changes it to `CREATED` and clears `execution_claimed_at`;
4. the next real claim changes it to `IN_FLIGHT`, generation 1, with a fresh database timestamp; and
5. generation 1 completes normally, writes the expected terminal diagnostics, and clears `execution_claimed_at`.

Construct the generation-0 capability only inside this adversarial integration test; production claim can never return
it. This directly proves both the repository predicate and the service rule that a zero-row parent transition cannot
create successor work.

- [ ] **Step 7: Remove the old claim/reset native methods and audit every new statement**

Remove `findByStatusAndUpdatedAtBefore(IN_FLIGHT, ...)`; reconciliation uses `findStaleInFlight`. Extend
`RepositoryPostgresAuditTest` to execute stale selection, claim, success, failure, reset-hit, and reset-miss statements
against PostgreSQL. The audit must prove that the only production transition into `IN_FLIGHT` sets generation and claim
time atomically, and that every completion/recovery transition out of `IN_FLIGHT` clears claim time in the same
statement. Exercise both invalid shapes of the consistency constraint as future-bypass detection.

- [ ] **Step 8: Verify Task 1**

```bash
./mvnw test -Dtest=AttemptExecutionRepositoryPostgresTest,AttemptExecutionMigrationLifecyclePostgresTest,AttemptRepositoryTest,RepositoryPostgresAuditTest
```

Expected: all pass; SQL logs show the positive-generation completion predicate, generation-scoped reset, and the full
migrated-generation-0 recovery lifecycle.

- [ ] **Step 9: Commit Task 1**

```bash
git add src/main/resources/db/migration/V12__add_attempt_execution_fencing.sql \
  src/main/java/com/example/relay/attempt/application/AttemptExecution.java \
  src/main/java/com/example/relay/attempt/application/AttemptMutationOutcome.java \
  src/main/java/com/example/relay/attempt/domain/Attempt.java \
  src/main/java/com/example/relay/attempt/infrastructure/AttemptExecutionCandidate.java \
  src/main/java/com/example/relay/attempt/infrastructure/AttemptExecutionClaim.java \
  src/main/java/com/example/relay/attempt/infrastructure/AttemptExecutionRepository.java \
  src/main/java/com/example/relay/attempt/infrastructure/AttemptExecutionRepositoryImpl.java \
  src/main/java/com/example/relay/attempt/infrastructure/AttemptRepository.java \
  src/test/java/com/example/relay/attempt/infrastructure/AttemptExecutionMigrationLifecyclePostgresTest.java \
  src/test/java/com/example/relay/attempt/infrastructure/AttemptExecutionRepositoryPostgresTest.java \
  src/test/java/com/example/relay/attempt/infrastructure/AttemptRepositoryTest.java \
  src/test/java/com/example/relay/common/RepositoryPostgresAuditTest.java
git commit -m "feat: add attempt execution generation fencing primitives"
```

---

### Task 2: Fence success, final failure, the CREATED gap, and ABA in AttemptService

**Files:**

- Modify: `src/main/java/com/example/relay/attempt/application/AttemptService.java`
- Modify: `src/test/java/com/example/relay/attempt/application/AttemptServiceTest.java`
- Create: `src/test/java/com/example/relay/attempt/application/AttemptExecutionFencingIntegrationTest.java`
- Modify: callers in dispatcher/reconciliation tests only enough to compile with `Optional<AttemptExecution>`

**Consumes:** Task 1 repository contract and locked application types.

**Produces:** Fenced service methods with no bare-Attempt completion overloads.

**Invariant established:** A lost generation cannot complete in `CREATED`, cannot complete after ABA, and cannot overwrite a newer terminal result.

- [ ] **Step 1: Add committed-state failing tests**

Create helper `claim(UUID)` that returns `AttemptExecution`. Add these test methods:

```java
staleFailureAfterNewerSuccess_doesNotOverwrite();
staleSuccessAfterNewerFailure_doesNotOverwrite();
staleSuccessDuringCreatedGap_isRejected();
staleGenerationAfterAba_isRejected();
activeGeneration_successAppliesOnce();
activeGeneration_finalDeadAppliesOnce();
```

For gap/ABA, execute real claim and `resetStuck`; backdate `execution_claimed_at` with `JdbcTemplate`. Assert exact row
status, diagnostics, generation, and `AttemptMutationOutcome`. A repeated call with the same execution must return
`OWNERSHIP_LOST` because the first call cleared `IN_FLIGHT`.

- [ ] **Step 2: Run failing-first evidence**

```bash
./mvnw test -Dtest=AttemptExecutionFencingIntegrationTest,AttemptServiceTest
```

Expected: compilation failures at the old `boolean claim(UUID, Instant)` and bare-Attempt completion signatures.

- [ ] **Step 3: Implement transactional claim capability**

In one `@Transactional` method, call `executionRepository.claim(attemptId)`, load the Attempt only on success, and
return:

```java
return Optional.of(new AttemptExecution(attempt, claim.generation(), claim.claimedAt()));
```

If the update returned a row but the Attempt cannot be loaded, throw `IllegalStateException`; that is corruption, not
ordinary ownership loss.

- [ ] **Step 4: Implement fenced success and non-retrying/final failure**

Truncate response/error before repository calls. Translate row count 1 to `APPLIED`, 0 to `OWNERSHIP_LOST`, and any
other count to `IllegalStateException`. Do not mutate/merge the detached Attempt. For success, clear old failure/retry
fields in SQL; for failure, set the complete diagnostic tuple explicitly.

- [ ] **Step 5: Verify Task 2**

```bash
./mvnw test -Dtest=AttemptExecutionFencingIntegrationTest,AttemptServiceTest,AttemptExecutionRepositoryPostgresTest
```

Expected: all pass and committed state remains unchanged on every stale call.

- [ ] **Step 6: Commit Task 2**

```bash
git add src/main/java/com/example/relay/attempt/application/AttemptService.java \
  src/test/java/com/example/relay/attempt/application/AttemptServiceTest.java \
  src/test/java/com/example/relay/attempt/application/AttemptExecutionFencingIntegrationTest.java \
  src/test/java/com/example/relay/deliveryengine/dispatcher/ReadyWorkDispatcherIntegrationTest.java \
  src/test/java/com/example/relay/deliveryengine/dispatcher/ReadyWorkDispatcherRecoveryIntegrationTest.java
git commit -m "feat: fence attempt completion by execution generation"
```

---

### Task 3: Preserve atomic retry creation while denying stale successor work

**Files:**

- Modify: `src/main/java/com/example/relay/attempt/application/AttemptService.java`
- Modify: `src/test/java/com/example/relay/attempt/application/AttemptExecutionFencingIntegrationTest.java`
- Modify: `src/test/java/com/example/relay/attempt/application/AttemptServiceMarkFailedAndCreateRetryAtomicityTest.java`

**Consumes:** Task 2 `AttemptExecution` and `AttemptMutationOutcome`.

**Produces:** One fenced transaction for parent failure plus retry insertion.

**Invariant established:** A stale owner creates zero successor rows; an active owner creates exactly one, and parent/child roll back together.

- [ ] **Step 1: Add retry-specific failing tests**

Add:

```java
staleFailureAfterNewerSuccess_createsNoRetry();
staleSuccessAfterNewerFailureAndRetry_preservesParentAndChild();
staleFailureAfterNewerRetry_createsNoDuplicateChild();
staleFailureAfterFirstChildTerminates_createsNoSecondAttemptNumber();
activeOwner_createsExactlyOneRetry();
retryInsertFailure_rollsBackFencedParentUpdate();
```

The third case is the reproduced P04/P05 boundary: allow B's retry child to finish, then invoke A's stale failure and
assert the count for attempt number 2 remains one. Do not add a P05 unique constraint in this task.

- [ ] **Step 2: Run failing-first evidence**

```bash
./mvnw test -Dtest=AttemptExecutionFencingIntegrationTest,AttemptServiceMarkFailedAndCreateRetryAtomicityTest
```

Expected: stale calls currently reach child creation or the method still accepts a bare Attempt.

- [ ] **Step 3: Implement parent-first fenced transaction**

Within `markFailedAndCreateRetry`:

```java
int updated = executionRepository.markFailed(... AttemptStatus.FAILED_RETRYING ...);
if (updated == 0) return AttemptMutationOutcome.OWNERSHIP_LOST;
if (updated != 1) throw new IllegalStateException("unexpected parent update count: " + updated);
createRetry(execution.attempt(), nextRetryAt);
return AttemptMutationOutcome.APPLIED;
```

Keep the method `@Transactional`. Remove the old explicit `attemptRepository.flush()` used solely to order Hibernate's
parent merge before child insert; JDBC has already executed the parent update. Preserve retry number/due-time logic.

- [ ] **Step 4: Adapt rollback injection**

Retain the existing spy that throws on saving a `SCHEDULED` Attempt. Assert the parent remains `IN_FLIGHT` with the same
generation and non-null claim timestamp after rollback, and no child exists.

- [ ] **Step 5: Verify Task 3**

```bash
./mvnw test -Dtest=AttemptExecutionFencingIntegrationTest,AttemptServiceMarkFailedAndCreateRetryAtomicityTest
```

Expected: all pass; the stale terminal-child case no longer produces duplicate attempt number 2.

- [ ] **Step 6: Commit Task 3**

```bash
git add src/main/java/com/example/relay/attempt/application/AttemptService.java \
  src/test/java/com/example/relay/attempt/application/AttemptExecutionFencingIntegrationTest.java \
  src/test/java/com/example/relay/attempt/application/AttemptServiceMarkFailedAndCreateRetryAtomicityTest.java
git commit -m "feat: fence atomic retry creation"
```

---

### Task 4: Carry ownership through DeliveryWorker and acknowledge superseded work

**Files:**

- Create: `src/main/java/com/example/relay/deliveryengine/worker/ExecutionOwnershipMetrics.java`
- Modify: `src/main/java/com/example/relay/deliveryengine/worker/DeliveryWorker.java`
- Create: `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerOwnershipFencingIntegrationTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerAckLifecycleTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerInFlightStateTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerIntegrationTest.java`

**Consumes:** Fenced service API from Tasks 2–3 and Spring AMQP async-return behavior.

**Produces:** Worker-owned `AttemptExecution` propagation, normal ownership-loss completion, gated dead-letter publication, counters/logs.

**Invariant established:** Stale/duplicate Rabbit work does not redeliver indefinitely, perform another HTTP call after failed claim, mutate state, or publish dead-letter work.

- [ ] **Step 1: Write controlled-transport A/B tests**

Use invocation-indexed `CountDownLatch` gates in a mocked `WebhookHttpTransport`:

- invocation A claims and blocks;
- test backdates `execution_claimed_at`, invokes reconciliation/reset, then dispatches B;
- B returns the chosen result and commits;
- A is released with the opposite result.

Cover A-failure/B-success and A-success/B-failure-with-retry. Assert the final parent/child rows exactly match B, two
HTTP calls occurred, and releasing A creates no third call.

- [ ] **Step 2: Write Rabbit acknowledgment/no-loop tests**

With real RabbitMQ and listeners enabled, publish the original task and let A block. Reset and run the ready dispatcher
to publish B. After B commits, release A and assert via the management API that `messages_unacknowledged` returns to
zero. Track transport calls for a bounded observation window using Awaitility stability assertions; assert the count
stays two. The correctness ordering is latch-driven; the broker-statistics wait is observation only.

Also publish duplicate tasks while the row is already `IN_FLIGHT` and after it is terminal. Assert neither duplicate
calls transport and both drain normally.

- [ ] **Step 3: Add attempt-6 dead-letter fencing tests**

For stale attempt 6 failure, assert `OWNERSHIP_LOST`, no `DEAD` overwrite, and no message in `delivery.deadletter`. For
the active owner, add `activeFinalFailure_commitsDeadBeforeDeadLetterPublication`. Intercept
`publishToRoutingKey` with a spy/mock answer and, inside that callback, read the Attempt using a `TransactionTemplate`
with `PROPAGATION_REQUIRES_NEW` (or an explicitly independent JDBC connection). Before allowing publication to return,
assert the independent transaction sees `DEAD`, the final response/error/latency diagnostics, and null
`execution_claimed_at`. Record the callback with a latch/atomic flag and assert it runs exactly once. This proves
commit-before-publication rather than mere Java call order. Keep the existing synchronous transactional service
boundary; do not add an outbox. Stop the notifier during the separate queue-observation assertion so it remains
deterministic.

- [ ] **Step 4: Run failing-first evidence**

```bash
./mvnw test -Dtest=DeliveryWorkerOwnershipFencingIntegrationTest,DeliveryWorkerAckLifecycleTest,DeliveryWorkerInFlightStateTest
```

Expected: compilation failure until the worker carries `AttemptExecution`; against old behavior, stale result/state
assertions fail.

- [ ] **Step 5: Implement worker propagation and ownership-loss handling**

Change process flow to:

```java
Optional<AttemptExecution> claimed = attemptService.claim(attemptId);
if (claimed.isEmpty()) {
    log.info("Attempt {} is not claimable; acknowledging duplicate or obsolete task", attemptId);
    return;
}
deliver(claimed.orElseThrow());
```

Every completion checks its returned outcome. `OWNERSHIP_LOST` calls one helper that reads current Attempt state for
diagnostics, increments the bounded counter, logs at INFO, and returns normally. `APPLIED` final failure alone calls
`publishToRoutingKey`. Do not catch arbitrary exceptions around the worker.

- [ ] **Step 6: Implement bounded metrics**

`ExecutionOwnershipMetrics` uses `MeterRegistry.counter(...)` with counter name
`relay.delivery.execution.ownership.lost` and tags `completion` plus enum `current_status` (or `missing`). Do not tag
Attempt ID/generation. Unit-test with `SimpleMeterRegistry` through worker integration assertions.

- [ ] **Step 7: Preserve unexpected-exception behavior**

Keep `unexpectedRuntimeFailure_remainsExceptional_andDoesNotPersistFailure` and
`unexpectedException_doesNotRequeueMessage` passing. Ownership loss is handled explicitly; programming defects still
complete the future exceptionally and are rejected without requeue.

- [ ] **Step 8: Verify Task 4**

```bash
./mvnw test -Dtest=DeliveryWorkerOwnershipFencingIntegrationTest,DeliveryWorkerAckLifecycleTest,DeliveryWorkerInFlightStateTest,DeliveryWorkerIntegrationTest
```

Expected: all pass, including real Rabbit queue drain and no-loop evidence.

- [ ] **Step 9: Commit Task 4**

```bash
git add src/main/java/com/example/relay/deliveryengine/worker/ExecutionOwnershipMetrics.java \
  src/main/java/com/example/relay/deliveryengine/worker/DeliveryWorker.java \
  src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerOwnershipFencingIntegrationTest.java \
  src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerAckLifecycleTest.java \
  src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerInFlightStateTest.java \
  src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerIntegrationTest.java
git commit -m "feat: acknowledge superseded delivery executions safely"
```

---

### Task 5: Make reconciliation an observed-generation revocation

**Files:**

- Modify: `src/main/java/com/example/relay/deliveryengine/reconciliation/ReconciliationSweeper.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/reconciliation/ReconciliationSweeperIntegrationTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/scheduling/ScheduledLoopGatingTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/dispatcher/ReadyWorkDispatcherRecoveryIntegrationTest.java`

**Consumes:** `Attempt.executionGeneration`, `executionClaimedAt`, and generation-scoped `resetStuck`.

**Produces:** Recovery decisions based on claim ownership/time, not generic `updated_at`.

**Invariant established:** Reconciliation revokes only the stale generation it observed, and genuinely abandoned Attempts still recover through durable ready work.

- [ ] **Step 1: Add reconciliation race tests**

Add:

```java
staleObservedGeneration_cannotResetNewOwner();
delayedObservedGeneration_afterReplacementTerminal_cannotResetOrEraseDiagnostics();
completionBeforeReset_causesResetToLose();
resetBeforeCompletion_fencesOldOwnerImmediately();
abandonedGeneration_resetsAndIsReclaimedAtHigherGeneration();
updatedAtChange_doesNotReplaceExecutionClaimAgeAuthority();
```

The first test captures generation N, resets/reclaims to N+1, then calls reset with observed N and asserts row count 0.
Use direct timestamp backdating, not waiting 90 seconds.

For `delayedObservedGeneration_afterReplacementTerminal_cannotResetOrEraseDiagnostics`, gate the real sweeper after
`findStaleInFlight` has returned candidate `(id, N, claimedAt)` but before it calls `resetStuck`. While it is gated,
reset N, claim N+1, and complete N+1 to a terminal state with distinctive diagnostics. Release the original sweeper.
Assert its `resetStuck(id, N, grace)` affects zero rows and that the newer terminal status, generation, response/error/
latency diagnostics, and null claim timestamp are byte-for-byte/value-for-value unchanged. Use latches around a spying
repository delegate; do not replace the real selection or reset SQL and do not use timing sleeps.

- [ ] **Step 2: Run failing-first evidence**

```bash
./mvnw test -Dtest=ReconciliationSweeperIntegrationTest,ReadyWorkDispatcherRecoveryIntegrationTest,ScheduledLoopGatingTest
```

Expected: compilation failures because the sweeper still calls the old timestamp signature and selects by `updatedAt`.

- [ ] **Step 3: Implement claim-time candidate selection and generation reset**

Compute no application-clock threshold. Call `findStaleInFlight(inFlightGrace, batchSize)`, whose SQL uses PostgreSQL
`CURRENT_TIMESTAMP`; pass each candidate's observed generation and the configured `Duration` to `resetStuck`, which
rechecks the same database-time age predicate before revocation.

- [ ] **Step 4: Add revocation metrics/logging**

Extend `ExecutionOwnershipMetrics` with `relay.delivery.execution.revoked{result=revoked|lost_race}`. Successful
revocation logs Attempt ID, generation, and age at WARN. A zero-row race logs at INFO. Do not emit payload/URL.

- [ ] **Step 5: Verify Task 5**

```bash
./mvnw test -Dtest=ReconciliationSweeperIntegrationTest,ReadyWorkDispatcherRecoveryIntegrationTest,ScheduledLoopGatingTest,AttemptExecutionFencingIntegrationTest
```

Expected: all pass; recovered work is republished through `ReadyWorkDispatcher`, not directly by the sweeper.

- [ ] **Step 6: Commit Task 5**

```bash
git add src/main/java/com/example/relay/deliveryengine/reconciliation/ReconciliationSweeper.java \
  src/main/java/com/example/relay/deliveryengine/worker/ExecutionOwnershipMetrics.java \
  src/test/java/com/example/relay/deliveryengine/reconciliation/ReconciliationSweeperIntegrationTest.java \
  src/test/java/com/example/relay/deliveryengine/scheduling/ScheduledLoopGatingTest.java \
  src/test/java/com/example/relay/deliveryengine/dispatcher/ReadyWorkDispatcherRecoveryIntegrationTest.java
git commit -m "feat: revoke stale attempt generations during reconciliation"
```

---

### Task 6: Prove replay separation and P01/P02/P03/capacity non-regression

**Files:**

- Modify only where claim signature migration requires it:
  - `src/test/java/com/example/relay/delivery/application/DeliveryReplayLifecycleIntegrationTest.java`
  - `src/test/java/com/example/relay/delivery/application/DeliveryReplayConcurrencyPostgresTest.java`
  - `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerScheduledIsolationIntegrationTest.java`
  - `src/test/java/com/example/relay/deliveryengine/worker/AbstractDeliveryWorkerAggregateCapacityTest.java`
- No P01/P02/P03 production changes.

**Consumes:** Completed ownership protocol.

**Produces:** Cross-project regression evidence and proof that replay-created Attempts independently acquire generation 1.

**Invariant established:** P04 is narrow: it preserves wire bytes, bounded responses, destination enforcement, replay semantics, and worker capacity.

- [ ] **Step 1: Add replay ownership assertions**

In replay lifecycle coverage, assert a newly created replay has generation 0/null claim time, then claim it and assert
generation 1/non-null claim time. Do not alter replay attempt-number or eligibility assertions.

- [ ] **Step 2: Run P01/P02/P03 focused regressions**

```bash
./mvnw test -Dtest=HmacSignerTest,DeliveryWorkerAckLifecycleTest,BoundedResponseBodyCaptureTest,BoundedApacheResponseBodyConsumerTest,ApacheResponseConsumptionIntegrationTest,ApacheWebhookHttpTransportTest,ApacheWebhookHttpTransportDeadlineTest,PolicyEnforcingDnsResolverTest,WebhookDestinationAdversarialIntegrationTest,WebhookTlsIdentityIntegrationTest
```

Expected: all pass unchanged.

- [ ] **Step 3: Run lifecycle/replay/recovery regressions**

```bash
./mvnw test -Dtest=AttemptExecutionFencingIntegrationTest,AttemptServiceMarkFailedAndCreateRetryAtomicityTest,DeliveryReplayConcurrencyPostgresTest,DeliveryReplayLifecycleIntegrationTest,ReadyWorkDispatcherIntegrationTest,ReadyWorkDispatcherRecoveryIntegrationTest,ReconciliationSweeperIntegrationTest,DeliveryWorkerScheduledIsolationIntegrationTest
```

Expected: all pass; P05's existing characterization remains outside P04.

- [ ] **Step 4: Run worker capacity regressions**

```bash
./mvnw test -Dtest=DeliveryWorkerConcurrencyTest,DeliveryWorkerAggregateCapacityFourByTenTest,DeliveryWorkerAggregateCapacityOneByFortyTest
```

Expected: all pass with the same configured aggregate ceiling and backpressure behavior.

- [ ] **Step 5: Commit signature-only test adaptations if needed**

```bash
git add src/test/java/com/example/relay/delivery/application/DeliveryReplayLifecycleIntegrationTest.java \
  src/test/java/com/example/relay/delivery/application/DeliveryReplayConcurrencyPostgresTest.java \
  src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerScheduledIsolationIntegrationTest.java \
  src/test/java/com/example/relay/deliveryengine/worker/AbstractDeliveryWorkerAggregateCapacityTest.java
git diff --cached --quiet || git commit -m "test: verify fencing compatibility across delivery lifecycle"
```

---

### Task 7: Full verification and rollout evidence

**Files:**

- Modify: `docs/reviews/probes/README.md` only to append the post-P04 verification commands/results after implementation.
- Do not rewrite the historical pre-P04 observed-output section.

**Consumes:** All implementation tasks.

**Produces:** Final suite evidence, complete production Attempt-state-writer inventory, migration audit, and deployment runbook note.

**Invariant established:** The complete repository accepts the new schema; every production Attempt lifecycle writer is classified; no authoritative `IN_FLIGHT` mutation can bypass fencing.

- [ ] **Step 1: Produce the complete Attempt-state-writer inventory**

```bash
rg -n --glob 'src/main/**' '\.setStatus\(|new Attempt\(|attemptRepository\.(save|saveAll|saveAndFlush)|UPDATE attempts|update attempts|SET status|status\s*='
rg -n --glob 'src/main/resources/db/migration/*.sql' 'attempts|status'
```

Append a writer matrix to `docs/reviews/probes/README.md`. It must enumerate every production `Attempt.status` setter,
every entity save path capable of persisting status, every custom repository mutation, every hand-written/native SQL
mutation of the Attempt lifecycle, and migration DML. Include field-only ready-publication and dead-notification SQL so
the audit does not mistake “does not change status” for “was not inspected.” For every entry record:

- transition(s) or fields it can mutate;
- ownership domain: execution, ready-publication, scheduling, notification, migration, or read-only guard;
- complete predicate/guard;
- whether it can operate while the row is `IN_FLIGHT`; and
- why it cannot bypass execution fencing or the claim-timestamp consistency constraint.

Acceptance requires zero unclassified production writers and positive evidence that:

1. the sole runtime entry into `IN_FLIGHT` is claim, which increments generation and sets claim time atomically;
2. every authoritative completion from `IN_FLIGHT` requires `id + IN_FLIGHT + matching positive execution_generation` and clears claim time;
3. the sole `IN_FLIGHT -> CREATED` recovery mutation is generation-scoped reconciliation, which rechecks grace and clears claim time;
4. scheduling, ready-publication, and notification paths either exclude `IN_FLIGHT` from status mutation or do not mutate status/claim fields; and
5. PostgreSQL rejects both inconsistency shapes: `IN_FLIGHT` without a claim timestamp and non-`IN_FLIGHT` with one.

- [ ] **Step 2: Retain the obsolete-API and completion-SQL greps**

```bash
rg -n "markSucceeded\(Attempt|markFailed\(Attempt|markFailedAndCreateRetry\(Attempt|claim\([^)]*,\s*Instant|findByStatusAndUpdatedAtBefore\(AttemptStatus.IN_FLIGHT" src/main
rg -n "UPDATE attempts" src/main/java/com/example/relay/attempt
```

Expected: the obsolete-API command has no matches. Every authoritative completion SQL shown by the second command has
the positive-generation ownership predicate; the generation-scoped reset is the only `IN_FLIGHT -> CREATED` SQL.
These greps supplement, but do not replace, the positive writer inventory in Step 1.

- [ ] **Step 3: Audit migration shape**

```bash
./mvnw test -Dtest=AttemptExecutionRepositoryPostgresTest,AttemptExecutionMigrationLifecyclePostgresTest,RepositoryPostgresAuditTest
```

Expected: V12 applies from a clean database and every hand-written query executes on PostgreSQL.

- [ ] **Step 4: Run the complete suite**

```bash
./mvnw test
```

Expected: zero failures and zero errors. If a failure appears, use `superpowers:systematic-debugging`; do not weaken a
concurrency assertion or change retry/grace values to obtain green output.

- [ ] **Step 5: Append exact evidence and rollout sequence**

Record the focused/full commands, test counts, revision, and the required deployment order: quiesce old background
writers, migrate, deploy only fenced binaries, resume, observe counters. Explicitly state that mixed-version rolling
deployment is unsafe. Include the completed writer matrix and its zero-unclassified-writer conclusion.

- [ ] **Step 6: Commit verification documentation**

```bash
git add docs/reviews/probes/README.md
git commit -m "docs: record P04 fencing verification and rollout"
```

## Plan self-review

- **Spec coverage:** Tasks 1–5 cover schema, migrated generation-0 recovery, claim, every completion path, atomic retry
  insertion, reconciliation, ABA, Rabbit semantics, committed-before-publication final `DEAD`, observability, and
  fixed-lease timing. Task 6 covers replay separation and P01–P03/capacity regressions. Task 7 covers the complete
  production writer audit, migration, and rollout evidence.
- **Type consistency:** All tasks use `AttemptExecution`, `AttemptMutationOutcome`, `AttemptExecutionClaim`, and the
  method signatures in Interface lock. No bare-Attempt completion overload survives.
- **Failure semantics:** Zero-row fenced mutations return `OWNERSHIP_LOST`; active-owner programming/persistence errors
  remain exceptional. Only `APPLIED` final failure publishes dead-letter work.
- **Scope:** No P05 sequence constraint, scheduler redesign, lease renewal, retry-policy change, or transport redesign is
  included.
