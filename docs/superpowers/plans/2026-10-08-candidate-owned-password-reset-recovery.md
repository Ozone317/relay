# Candidate-Owned Password-Reset Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make password-reset recovery replace only the exact still-eligible candidate selected by the sweeper, with deterministic PostgreSQL proof for every relevant race.

**Architecture:** Keep the unlocked bounded scan as advisory. A new transaction locks the user first, locks the selected token by primary key, revalidates all eligibility from that row, retires only that row, and atomically inserts its successor. The outer service publishes only after a committed `REISSUED` result; expected race loss is a no-op.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data JPA/Hibernate, PostgreSQL 16/Testcontainers, JUnit 5, AssertJ, Mockito, Maven

**Spec:** `docs/superpowers/specs/2026-10-08-candidate-owned-password-reset-recovery-design.md`

## Global Constraints

- Preserve hashed, single-use reset tokens and all password-reset HTTP contracts.
- Preserve enumeration resistance, Redis cooldown/hourly admission, activation, refresh-session revocation, token TTL, grace, batch, and max-window behavior.
- Preserve User-before-token lock ordering for every path that acquires both resource classes.
- Publish a recovery email only after a successful recovery transaction commits.
- Losing recovery races are no-ops: no token and no email.
- Do not add a schema migration, generic outbox, distributed lock, leader election, scheduler redesign, authentication rewrite, or verification-email recovery.
- Old and new password-reset recovery schedulers must not run concurrently during rollout; every old scheduler must be stopped/disabled and every in-flight old recovery execution must drain or be terminated before any new scheduler is enabled.
- The investigation characterization currently asserts defective behavior; no defect-accepting assertion may remain in the final suite.

## Review Focus

- Candidate ID mismatch or stale T0 after a newer request must return `LOST_RACE` without touching T1.
- Dispatch confirmation before the candidate lock must win; confirmation after the recovery lock must block and be ordered after recovery.
- Recovery must evaluate grace, expiry, and max-window from locked durable fields at one post-lock decision instant.
- A failure after exact candidate retirement but before successor commit must restore T0 and suppress publication.
- Two sweepers must not create or publish two successors, even when both scans return T0 before either transaction starts.

---

## File map

### Production files to modify

- `src/main/java/com/example/relay/user/infrastructure/PasswordResetTokenRepository.java`
  - add the exact primary-key native candidate lock query.
- `src/main/java/com/example/relay/user/application/PasswordResetTokenService.java`
  - add recovery outcome/result types and the candidate-owned transaction;
  - remove the candidate-unaware recovery method after callers migrate;
  - keep ordinary issuance and consumption semantics unchanged.
- `src/main/java/com/example/relay/user/application/PasswordResetService.java`
  - accept candidate identity/configured durations;
  - publish only a committed `REISSUED` result.
- `src/main/java/com/example/relay/user/recovery/PasswordResetEmailRecoverySweeper.java`
  - treat scan rows as hints and delegate authoritative eligibility/exhaustion to the transaction.

### Tests to modify or create

- `src/test/java/com/example/relay/user/infrastructure/PasswordResetTokenRepositoryTest.java`
  - exact candidate lock SQL behavior and basic mapping.
- `src/test/java/com/example/relay/user/infrastructure/PasswordResetTokenCandidateLockPostgresTest.java`
  - new row-scope lock proof using independent transactions.
- `src/test/java/com/example/relay/user/application/PasswordResetTokenServiceTest.java`
  - outcome, exact-candidate retirement, lock order, and exact boundary tests.
- `src/test/java/com/example/relay/user/application/PasswordResetRecoveryRollbackPostgresTest.java`
  - new forced rollback after retirement/before successor commit, including outer-service proof that no reset email is published.
- `src/test/java/com/example/relay/user/application/PasswordResetServiceTest.java`
  - publication only for `REISSUED`.
- `src/test/java/com/example/relay/user/recovery/PasswordResetEmailRecoverySweeperTest.java`
  - candidate ID/user ID delegation and outcome logging/behavior.
- `src/test/java/com/example/relay/user/recovery/PasswordResetEmailRecoverySweeperIntegrationTest.java`
  - valid recovery liveness, exhaustion, and expiry after orchestration changes.
- `src/test/java/com/example/relay/user/recovery/PasswordResetMaintenanceConcurrencyPostgresTest.java`
  - replace two-successor assertions with one-owner safety and add lock-order/backend evidence.
- `src/test/java/com/example/relay/user/recovery/characterization/PasswordResetRecoveryRaceCharacterizationPostgresTest.java`
  - move to the normal recovery package/name and invert every unsafe assertion into permanent safety assertions; remove the `characterization` class/package from the final tree.

### Documentation to update after implementation evidence

- `docs/reviews/2026-10-08-password-reset-recovery-investigation.md`
  - append implementation verification results without rewriting historical reproduction evidence.
- `docs/superpowers/specs/2026-10-08-candidate-owned-password-reset-recovery-design.md`
  - change status only after the implementation review gate passes; preserve the mixed-version restriction.

---

### Task 1: Add and prove the exact candidate-row lock

**Files:**
- Modify: `src/main/java/com/example/relay/user/infrastructure/PasswordResetTokenRepository.java`
- Modify: `src/test/java/com/example/relay/user/infrastructure/PasswordResetTokenRepositoryTest.java`
- Create: `src/test/java/com/example/relay/user/infrastructure/PasswordResetTokenCandidateLockPostgresTest.java`

**Interfaces:**
- Produces: `Optional<PasswordResetToken> findByIdForUpdate(UUID tokenId)`
- SQL contract: primary-key equality on `password_reset_tokens`, no join/range/user predicate, `FOR UPDATE`

- [ ] **Step 1: Write the repository mapping test before adding the method**

Add a test that persists two token rows, calls `findByIdForUpdate(firstId)` inside the `@DataJpaTest` transaction, and asserts the returned ID and durable fields are those of the first row.

```java
@Test
void findByIdForUpdate_returnsTheExactToken() {
    PasswordResetToken first = persisted("lock-first", now.plusSeconds(1800));
    persisted("lock-second", now.plusSeconds(1800));

    PasswordResetToken locked = underTest.findByIdForUpdate(first.getId()).orElseThrow();

    assertEquals(first.getId(), locked.getId());
}
```

- [ ] **Step 2: Run the focused test and verify the missing-method failure**

Run:

```bash
./mvnw -Dtest=PasswordResetTokenRepositoryTest#findByIdForUpdate_returnsTheExactToken test
```

Expected: test compilation fails because `findByIdForUpdate(UUID)` is not defined.

- [ ] **Step 3: Add the exact native lock query**

Add exactly this method to `PasswordResetTokenRepository`:

```java
@Query(value = """
        SELECT *
        FROM password_reset_tokens
        WHERE id = :tokenId
        FOR UPDATE
        """, nativeQuery = true)
Optional<PasswordResetToken> findByIdForUpdate(UUID tokenId);
```

Do not use `@Lock` on a derived entity query: this task requires auditable SQL that locks only the token table row.

- [ ] **Step 4: Run the repository mapping test**

Run the Step 2 command.

Expected: one test passes.

- [ ] **Step 5: Write the independent-transaction PostgreSQL row-scope test**

Create `PasswordResetTokenCandidateLockPostgresTest` as `@SpringBootTest`, `@Tag("integration")`, `@EnableTestBackgroundExecution({})`, and `SharedPostgresContainer`. Use two `TransactionTemplate` calls on separate executor threads:

1. transaction A locks T0 with `findByIdForUpdate`, records its backend PID, signals `t0Locked`, and waits on `releaseT0`;
2. transaction B locks T1 with the same method and must complete while A is still open;
3. transaction C attempts to lock T0, records its PID, and must be observed in `pg_stat_activity` with `wait_event_type = 'Lock'` and transaction A's PID in `pg_blocking_pids`;
4. release A and assert C completes.

Use `CountDownLatch`, bounded `Future#get`, and Awaitility polling of `pg_stat_activity`; do not use sleeps.

- [ ] **Step 6: Run the exact lock-scope test**

Run:

```bash
./mvnw -Dtest=PasswordResetTokenCandidateLockPostgresTest test
```

Expected: T1 lock completes before `releaseT0`; T0 competitor is observed blocked by A; all futures complete after release.

- [ ] **Step 7: Inspect emitted SQL**

Run:

```bash
./mvnw -Dtest=PasswordResetTokenRepositoryTest#findByIdForUpdate_returnsTheExactToken \
  -Dspring.jpa.show-sql=true -Dspring.jpa.properties.hibernate.format_sql=false test
```

Expected SQL contains `from password_reset_tokens where id=? for update` and contains no join in that statement.

- [ ] **Step 8: Task-level adversarial review**

Review the diff and answer in the task notes:

- Does the query use only the primary key and lock only `password_reset_tokens`?
- Does the concurrency test prove T1 is not locked, rather than merely assuming it?
- Are all latch waits and futures bounded?
- Does any test rely on thread start order or sleep?

Correct findings before proceeding.

- [ ] **Step 9: Commit Task 1**

```bash
git add src/main/java/com/example/relay/user/infrastructure/PasswordResetTokenRepository.java \
  src/test/java/com/example/relay/user/infrastructure/PasswordResetTokenRepositoryTest.java \
  src/test/java/com/example/relay/user/infrastructure/PasswordResetTokenCandidateLockPostgresTest.java
git commit -m "test: prove exact password reset candidate locking"
```

---

### Task 2: Implement the candidate-owned recovery transaction

**Files:**
- Modify: `src/main/java/com/example/relay/user/application/PasswordResetTokenService.java`
- Modify: `src/test/java/com/example/relay/user/application/PasswordResetTokenServiceTest.java`
- Create: `src/test/java/com/example/relay/user/application/PasswordResetRecoveryRollbackPostgresTest.java`

**Interfaces:**
- Consumes: `PasswordResetTokenRepository.findByIdForUpdate(UUID)` from Task 1
- Produces: `RecoveryOutcome`, `RecoveryAttempt`, and
  `RecoveryAttempt recoverCandidate(UUID candidateId, UUID observedUserId, Duration grace, Duration maxRecoveryWindow)`
- Preserves: `IssuedResetToken`, ordinary `issue`, and consumption interfaces

- [ ] **Step 1: Add failing unit tests for expected outcomes and lock order**

Add focused tests with these names:

```text
recoverCandidate_returnsLostRace_whenUserIsMissing
recoverCandidate_returnsLostRace_whenCandidateIsMissing
recoverCandidate_returnsLostRace_whenCandidateBelongsToAnotherUser
recoverCandidate_returnsLostRace_whenCandidateIsUsedDispatchedExpiredOrNotStale
recoverCandidate_returnsExhausted_andRetiresOnlyCandidate_atWindowBoundary
recoverCandidate_reissues_andCarriesLockedFirstRequestedAt
recoverCandidate_locksUserBeforeCandidate
recoverCandidate_returnsLostRace_whenExactRetirementUpdatesZeroRows
```

For lock ordering, use Mockito `InOrder`:

```java
InOrder order = inOrder(userRepository, passwordResetTokenRepository);
order.verify(userRepository).lockForUpdate(userId);
order.verify(passwordResetTokenRepository).findByIdForUpdate(candidateId);
```

For successful recovery, verify `invalidateAllForUser` is never called and `giveUpOn(candidateId, fixedNow)` is called exactly once.

- [ ] **Step 2: Add deterministic exact-boundary tests with test-only static time**

In synchronous unit tests, use a fixed `Instant decisionTime` and Mockito `mockStatic(Instant.class, CALLS_REAL_METHODS)` scoped to one test invocation. Make `Instant.now()` return `decisionTime` only while calling `recoverCandidate`.

Cover:

```text
updated_at = decisionTime - grace - 1ns  -> eligible
updated_at = decisionTime - grace        -> LOST_RACE
updated_at = decisionTime - grace + 1ns  -> LOST_RACE
expires_at = decisionTime - 1ns          -> LOST_RACE
expires_at = decisionTime                -> LOST_RACE
expires_at = decisionTime + 1ns          -> eligible
first_requested_at = cutoff - 1ns        -> EXHAUSTED
first_requested_at = cutoff              -> EXHAUSTED
first_requested_at = cutoff + 1ns        -> eligible
```

Do not add a production `Clock` or time-provider abstraction.

- [ ] **Step 3: Run the new unit tests and verify failure**

Run:

```bash
./mvnw -Dtest=PasswordResetTokenServiceTest test
```

Expected: new tests fail to compile because the recovery types/method do not exist.

- [ ] **Step 4: Add the outcome/result types**

Add nested types with construction invariants:

```java
public enum RecoveryOutcome {
    REISSUED,
    EXHAUSTED,
    LOST_RACE
}

public record RecoveryAttempt(RecoveryOutcome outcome, IssuedResetToken issuedToken) {
    static RecoveryAttempt reissued(IssuedResetToken token) {
        return new RecoveryAttempt(RecoveryOutcome.REISSUED, token);
    }

    static RecoveryAttempt exhausted() {
        return new RecoveryAttempt(RecoveryOutcome.EXHAUSTED, null);
    }

    static RecoveryAttempt lostRace() {
        return new RecoveryAttempt(RecoveryOutcome.LOST_RACE, null);
    }
}
```

Keep the factories package-visible if only same-package tests/services need them. Validate in the compact constructor that `REISSUED` has a token and other outcomes do not.

- [ ] **Step 5: Implement the minimal candidate-owned transaction**

Add `@Transactional recoverCandidate` with this order:

```java
Optional<User> lockedUser = userRepository.lockForUpdate(observedUserId);
if (lockedUser.isEmpty()) {
    return RecoveryAttempt.lostRace();
}

Optional<PasswordResetToken> lockedCandidate =
        passwordResetTokenRepository.findByIdForUpdate(candidateId);
if (lockedCandidate.isEmpty()) {
    return RecoveryAttempt.lostRace();
}

Instant decisionTime = Instant.now();
PasswordResetToken candidate = lockedCandidate.get();
```

Check user identity, `usedAt`, `resetEmailDispatchedAt`, strict expiry, and strict grace against the locked row. For exhaustion, call exact `giveUpOn` and return `EXHAUSTED` only when it updates one row. For eligible recovery, call exact `giveUpOn`; on one row, generate/save/flush the successor using `lockedUser.get()` and `candidate.getFirstRequestedAt()`.

Do not call `invalidateAllForUser`, do not accept a stale `User`, and do not accept a stale chain timestamp.

- [ ] **Step 6: Run the service unit tests**

Run the Step 3 command.

Expected: all `PasswordResetTokenServiceTest` tests pass, including existing ordinary issue/consume coverage.

- [ ] **Step 7: Write the failing PostgreSQL rollback test**

Create `PasswordResetRecoveryRollbackPostgresTest` with a real eligible T0 and a `@MockitoSpyBean PasswordResetTokenRepository`. Persist/flush T0 before installing the stub, then make `saveAndFlush(any(PasswordResetToken.class))` throw only for the attempted successor. Invoke `recoverCandidate` and assert the exception. The production method executes `giveUpOn(T0)` before `saveAndFlush`, so this forces failure after exact retirement and before successor persistence/commit.

After the transaction ends, assert:

- T0 still has `used_at IS NULL` and `reset_email_dispatched_at IS NULL`;
- no successor exists;
- `first_requested_at` is unchanged.

Verify `giveUpOn(T0, decisionTime)` precedes the throwing `saveAndFlush` with Mockito `InOrder`.

Add a second test that invokes the same forced failure through `PasswordResetService.issueAndDispatchForRecovery(...)`, with `EmailDispatchPublisher` as a spy. Assert the exception propagates, T0 remains unchanged after rollback, no successor exists, and `publish` is never called. This proves the post-commit publication coupling through the outer production path, not only inside the token service.

- [ ] **Step 8: Run the rollback test**

Run:

```bash
./mvnw -Dtest=PasswordResetRecoveryRollbackPostgresTest test
```

Expected: one test passes and proves durable rollback.

- [ ] **Step 9: Task-level adversarial review**

Review:

- Is `Instant.now()` called only after both locks return?
- Is every eligibility value read from the locked entity?
- Is only `candidateId` retired?
- Can an exhausted row accidentally create a successor?
- Does zero-row retirement become `LOST_RACE`?
- Does rollback preserve T0?

Correct findings before proceeding.

- [ ] **Step 10: Commit Task 2**

```bash
git add src/main/java/com/example/relay/user/application/PasswordResetTokenService.java \
  src/test/java/com/example/relay/user/application/PasswordResetTokenServiceTest.java \
  src/test/java/com/example/relay/user/application/PasswordResetRecoveryRollbackPostgresTest.java
git commit -m "feat: add candidate-owned reset recovery transaction"
```

---

### Task 3: Switch orchestration and convert defect characterizations into safety regressions

**Files:**
- Modify: `src/main/java/com/example/relay/user/application/PasswordResetService.java`
- Modify: `src/main/java/com/example/relay/user/recovery/PasswordResetEmailRecoverySweeper.java`
- Modify: `src/main/java/com/example/relay/user/application/PasswordResetTokenService.java`
- Modify: `src/test/java/com/example/relay/user/application/PasswordResetServiceTest.java`
- Modify: `src/test/java/com/example/relay/user/recovery/PasswordResetEmailRecoverySweeperTest.java`
- Modify: `src/test/java/com/example/relay/user/recovery/PasswordResetEmailRecoverySweeperIntegrationTest.java`
- Move: `src/test/java/com/example/relay/user/recovery/characterization/PasswordResetRecoveryRaceCharacterizationPostgresTest.java` → `src/test/java/com/example/relay/user/recovery/PasswordResetRecoveryOwnershipPostgresTest.java`

**Interfaces:**
- Consumes: `PasswordResetTokenService.recoverCandidate(...)`
- Produces: candidate-aware `PasswordResetService.issueAndDispatchForRecovery(...)`
- Removes: `PasswordResetTokenService.reissueForRecovery(User, Instant, Instant)` and stale-entity recovery service signature

- [ ] **Step 1: Move and invert the existing characterization before production orchestration changes**

Rename the class/package to `PasswordResetRecoveryOwnershipPostgresTest`. Remove the class comment saying it asserts unsafe behavior. Convert its three tests:

```text
staleSelectedCandidateCannotInvalidateNewerDispatchConfirmedUserRequest
dispatchConfirmationOfSelectedCandidateWinsBeforeRecoveryLock
consumptionOfSelectedCandidateWinsBeforeRecoveryLock
```

Invert assertions:

- primary: exactly T0 and T1 exist; T1 is unused and confirmed; only the T1 user-request publication exists;
- confirmation: T0 remains unused and confirmed; no successor/recovery publication;
- consumption: T0 remains consumed; no successor/recovery reset-link publication.

- [ ] **Step 2: Run the converted ownership tests against old orchestration**

Run:

```bash
./mvnw -Dtest=PasswordResetRecoveryOwnershipPostgresTest test
```

Expected: all three fail on current candidate-unaware orchestration. Capture the assertion summaries in the implementation notes.

- [ ] **Step 3: Add failing outer-service unit tests**

Test that `issueAndDispatchForRecovery(candidateId, userId, grace, maxWindow)`:

- publishes exactly once with the successor ID for `REISSUED`;
- publishes zero times for `EXHAUSTED`;
- publishes zero times for `LOST_RACE`.

Verify the service passes only IDs/durations to `recoverCandidate`, not a `User` or `firstRequestedAt` snapshot.

- [ ] **Step 4: Add failing sweeper unit tests**

Make the sweeper tests verify each scan hint delegates:

```java
passwordResetService.issueAndDispatchForRecovery(
        candidate.getId(), candidate.getUser().getId(),
        properties.getGrace(), properties.getMaxRecoveryWindow());
```

The sweeper must not evaluate elapsed chain time itself and must not call `giveUpOnRecovery` separately.

- [ ] **Step 5: Change `PasswordResetService` orchestration**

Replace the stale-entity method with the candidate-aware signature. Call `recoverCandidate` through the transactional bean. Dispatch only when `outcome == REISSUED`, using the committed returned token/user/raw value. Return the outcome to the sweeper for logging.

- [ ] **Step 6: Change the sweeper**

Keep the finder/batch loop. Remove its pre-transaction max-window branch. For every hint, call the new service method with candidate ID, observed user ID, grace, and max window. Log:

- `REISSUED`: candidate recovered;
- `EXHAUSTED`: chain retired at bound;
- `LOST_RACE`: debug/info no-op, without warning that implies damage.

- [ ] **Step 7: Remove candidate-unaware recovery entry points**

Delete `reissueForRecovery(User, Instant, Instant)` and `giveUpOnRecovery(UUID, Instant)` if no caller remains. Keep the repository's exact `giveUpOn`, now used inside `recoverCandidate`.

Run:

```bash
rg -n 'reissueForRecovery|giveUpOnRecovery|issueAndDispatchForRecovery' src/main src/test
```

Expected: no candidate-unaware production call/signature remains.

- [ ] **Step 8: Run unit and converted ownership tests**

Run:

```bash
./mvnw -Dtest=PasswordResetTokenServiceTest,PasswordResetServiceTest,PasswordResetEmailRecoverySweeperTest,PasswordResetRecoveryOwnershipPostgresTest test
```

Expected: all pass; the historical stale-sweeper/newer-confirmed-token case is now a permanent safety regression.

- [ ] **Step 9: Update liveness/exhaustion integration assertions**

Adjust `PasswordResetEmailRecoverySweeperIntegrationTest` only for API/outcome changes. Preserve assertions that legitimate unchanged T0 produces exactly one successor with dispatch confirmation, exhausted T0 produces none, and expired rows remain untouched.

- [ ] **Step 10: Run the recovery integration suite**

```bash
./mvnw -Dtest=PasswordResetEmailRecoverySweeperIntegrationTest test
```

Expected: all tests pass with real PostgreSQL/RabbitMQ paths.

- [ ] **Step 11: Task-level adversarial review**

Review:

- Does any stale entity or timestamp authorize mutation?
- Can `LOST_RACE` reach `EmailDispatchPublisher`?
- Is publication still after proxy commit?
- Was the unsafe characterization removed rather than left green under old expectations?
- Does legitimate unchanged recovery still publish once?

Correct findings before proceeding.

- [ ] **Step 12: Commit Task 3**

```bash
git add src/main/java/com/example/relay/user/application/PasswordResetService.java \
  src/main/java/com/example/relay/user/application/PasswordResetTokenService.java \
  src/main/java/com/example/relay/user/recovery/PasswordResetEmailRecoverySweeper.java \
  src/test/java/com/example/relay/user/application/PasswordResetServiceTest.java \
  src/test/java/com/example/relay/user/recovery/PasswordResetEmailRecoverySweeperTest.java \
  src/test/java/com/example/relay/user/recovery/PasswordResetEmailRecoverySweeperIntegrationTest.java \
  src/test/java/com/example/relay/user/recovery/PasswordResetRecoveryOwnershipPostgresTest.java \
  src/test/java/com/example/relay/user/recovery/characterization/PasswordResetRecoveryRaceCharacterizationPostgresTest.java
git commit -m "fix: make password reset recovery candidate-owned"
```

---

### Task 4: Prove both database orderings for request and dispatch races

**Files:**
- Modify: `src/test/java/com/example/relay/user/recovery/PasswordResetRecoveryOwnershipPostgresTest.java`
- Modify: `src/test/java/com/example/relay/user/recovery/PasswordResetMaintenanceConcurrencyPostgresTest.java`

**Interfaces:**
- Consumes: candidate-owned production flow from Task 3
- Produces: permanent deterministic PostgreSQL safety/liveness matrix with backend-blocking evidence

- [ ] **Step 1: Add the ordinary-request-wins test**

Retain the post-scan gate from the characterization. After T0 is selected, run real `PasswordResetService.issueAndDispatch(user)` to commit T1, leave T1 dispatch-unconfirmed, release recovery, and assert:

- T0 used;
- T1 unused;
- no T2;
- one user-request publication only.

- [ ] **Step 2: Add the recovery-wins-then-request test with observed user-lock blocking**

Gate recovery inside its transaction after `findByIdForUpdate(T0)` but before `giveUpOn`. Record recovery's backend PID. Start ordinary issuance on another backend, record its PID, and poll `pg_stat_activity` until:

- ordinary issuance has `wait_event_type = 'Lock'`;
- its active query is the users lock query;
- `pg_blocking_pids(ordinaryPid)` contains `recoveryPid`.

Release recovery, await both commits, and assert:

- recovery successor was created/published once;
- later ordinary request invalidated that successor and created/published its own token;
- the ordinary request's token is the sole usable final token.

- [ ] **Step 3: Add the confirmation-wins test**

Retain the post-scan gate. Confirm T0 in a committed `TransactionTemplate`, release recovery, and assert no successor/publication. This is distinct from the newer-T1 primary race.

- [ ] **Step 4: Add the recovery-wins-then-confirmation test with observed token-lock blocking**

Gate recovery after `findByIdForUpdate(T0)` and before exact retirement. Start `claimResetEmailDispatch(T0)` in another transaction/backend. Poll until:

- confirmation backend has `wait_event_type = 'Lock'`;
- `pg_blocking_pids(confirmationPid)` contains `recoveryPid`;
- its active query is the dispatch-confirmation update.

Release recovery. Assert one successor and one recovery publication. After confirmation completes, T0 may have both `used_at` and `reset_email_dispatched_at`; the successor remains the sole usable token.

- [ ] **Step 5: Convert the two-sweeper test from defect acceptance to ownership safety**

Change `concurrentRecoveryCallersPreserveOneUsableTokenAndPersistedRequestTime` expectations:

- rows for the user: T0 plus exactly one successor, not three rows;
- invalidated rows: T0 only;
- usable rows: exactly one successor;
- publications: exactly one, not two;
- successor `first_requested_at` equals T0's persisted value.

Keep both sweepers gated after the real finder returns T0, then release together.

- [ ] **Step 6: Add User-before-token observed-blocking test**

Hold the user's row in an independent transaction. Start recovery and bind it to an exact backend PID at entry to `recoverCandidate`. Observe that PID blocked on the users `FOR NO KEY UPDATE` query. In a third transaction, acquire/release a lock on T0 successfully while recovery remains blocked, proving recovery has not reversed the order by locking T0 first. Release the user blocker and assert recovery completes.

- [ ] **Step 7: Run the complete concurrency classes**

```bash
./mvnw -Dtest=PasswordResetRecoveryOwnershipPostgresTest,PasswordResetMaintenanceConcurrencyPostgresTest test
```

Expected: all tests pass; no timeout/deadlock; every intended waiter is tied to exact backend PIDs and expected blocker PIDs.

- [ ] **Step 8: Repeat the concurrency run**

Run the Step 7 command three consecutive times.

Expected: all three runs pass. Any flaky timing is a test defect; replace missing state gates with latches/`pg_stat_activity` conditions rather than increasing sleeps (none should exist).

- [ ] **Step 9: Task-level adversarial review**

Review each race test's event sequence as a table of committed/locked states. Confirm:

- both request orderings are distinct and asserted;
- both confirmation orderings are distinct and asserted;
- blocking is observed at PostgreSQL, not inferred from futures;
- publication counts correspond to committed issuance winners;
- no defect-accepting two-successor assertion remains.

Correct findings before proceeding.

- [ ] **Step 10: Commit Task 4**

```bash
git add src/test/java/com/example/relay/user/recovery/PasswordResetRecoveryOwnershipPostgresTest.java \
  src/test/java/com/example/relay/user/recovery/PasswordResetMaintenanceConcurrencyPostgresTest.java
git commit -m "test: fence password reset recovery races"
```

---

### Task 5: Run regression gates and document rollout evidence

**Files:**
- Modify: `docs/reviews/2026-10-08-password-reset-recovery-investigation.md`
- Modify: `docs/superpowers/specs/2026-10-08-candidate-owned-password-reset-recovery-design.md`

**Interfaces:**
- Consumes: completed production/test changes
- Produces: review-ready evidence and explicit deployment restriction

- [ ] **Step 1: Run focused repository/service/recovery tests**

```bash
./mvnw -Dtest=PasswordResetTokenRepositoryTest,PasswordResetTokenCandidateLockPostgresTest,PasswordResetTokenServiceTest,PasswordResetRecoveryRollbackPostgresTest,PasswordResetServiceTest,PasswordResetEmailRecoverySweeperTest,PasswordResetEmailRecoverySweeperIntegrationTest,PasswordResetRecoveryOwnershipPostgresTest,PasswordResetMaintenanceConcurrencyPostgresTest test
```

Expected: zero failures/errors.

- [ ] **Step 2: Run adjacent auth and dispatch regressions**

```bash
./mvnw -Dtest=PasswordResetConcurrentRequestPostgresTest,PasswordResetConcurrentConfirmPostgresTest,PasswordResetTransactionRollbackIntegrationTest,PasswordResetActivatesPendingAccountIntegrationTest,EmailDispatchIntegrationTest,EmailVerificationConcurrentVerifyPostgresTest,EmailVerificationConcurrentResendPostgresTest test
```

Expected: zero failures/errors; activation, session, and dispatch semantics remain unchanged.

- [ ] **Step 3: Run all unit tests**

```bash
./mvnw test -DexcludedGroups=integration
```

Expected: zero failures/errors.

- [ ] **Step 4: Run formatting and compile gates**

```bash
./mvnw spotless:check
./mvnw -DskipTests package
```

Expected: both commands exit 0.

- [ ] **Step 5: Scan for obsolete APIs and unsafe assertions**

```bash
rg -n 'reissueForRecovery|giveUpOnRecovery|both recovery calls persist successor rows|publications.*hasSize\(2\)|unsafe current behavior|characterization' src/main src/test
```

Expected: no obsolete candidate-unaware production API, defect-accepting assertion, or characterization package/class remains. Legitimate unrelated uses of the word “characterization” must be reviewed individually rather than deleted mechanically.

- [ ] **Step 6: Update implementation evidence**

Append to the investigation report:

- implementation commit/HEAD;
- exact focused and adjacent commands with counts;
- confirmation that the old reproduction now passes as a safety regression;
- confirmation that no migration was added;
- confirmation that provider acceptance is still not inbox-delivery proof.

After all gates are green, update the design status to `IMPLEMENTED — PENDING OWNER REVIEW`. Do not mark it owner-approved or merged.

- [ ] **Step 7: Document the deployment gate verbatim in release notes/PR**

Include:

> Old and new password-reset recovery schedulers must not execute concurrently. Stop or disable recovery scheduling on every old instance and wait until every in-flight old recovery execution has drained or been terminated before enabling any new recovery scheduler. Ordinary request traffic may continue while recovery scheduling is disabled.

Do not add leader election or change scheduler architecture.

- [ ] **Step 8: Whole-change adversarial review**

Re-read the spec and inspect the complete diff. Challenge:

- candidate identity after every wait;
- all issuance/consume/verification lock orders;
- confirmation before and after candidate lock;
- exact one-email behavior for two sweepers;
- deadline carry-forward and exact boundary comparisons;
- rollback and post-commit publication;
- mixed-version deployment safety;
- absence of schema/API/unrelated changes.

Record substantive findings in the investigation appendix and fix them before rerunning affected gates.

- [ ] **Step 9: Run final whitespace/document checks**

```bash
git diff --check
rg -n 'T[B]D|T[O]DO|implement[ ]later|fill[ ]in[ ]details' \
  docs/reviews/2026-10-08-password-reset-recovery-investigation.md \
  docs/superpowers/specs/2026-10-08-candidate-owned-password-reset-recovery-design.md \
  docs/superpowers/plans/2026-10-08-candidate-owned-password-reset-recovery.md
```

Expected: `git diff --check` exits 0; placeholder scan returns no matches.

- [ ] **Step 10: Commit Task 5**

```bash
git add docs/reviews/2026-10-08-password-reset-recovery-investigation.md \
  docs/superpowers/specs/2026-10-08-candidate-owned-password-reset-recovery-design.md
git commit -m "docs: record candidate-owned recovery verification"
```

---

## Final implementation review gate

The change is ready for owner implementation review only when:

- all five task review checkpoints are resolved;
- the permanent T0/T1 regression leaves confirmed T1 valid and publishes no T2;
- both sides of request and confirmation races have deterministic PostgreSQL evidence;
- exact lock scope and User-before-token order are observed against backend PIDs;
- valid recovery and exhaustion liveness pass;
- rollback leaves T0 intact and publishes nothing;
- no unsafe characterization or duplicate-successor expectation remains;
- no migration or out-of-scope architecture change exists;
- the mixed-version scheduler restriction is present in the deployment notes;
- all verification commands and `git diff --check` pass freshly.
