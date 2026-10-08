# Password-Reset Recovery Investigation

**Date:** 2026-10-08
**Branch:** `main`
**HEAD:** `ff91dd1baf6277cbafcebddab352dc261dafc230` (`fix: prevent hidden Brevo rate-limit retries`)
**Scope:** Investigation and design only; no production implementation

## Executive conclusion

The September 23 password-reset email recovery race still exists on current `main`.

Current recovery preserves the observed candidate's `firstRequestedAt`, but not its identity. The sweeper selects T0 in an unlocked, completed repository read and later calls a recovery issuance method with only a stale `User` and timestamp. That transaction locks the user, invalidates **every** unused reset token for the user, and inserts a successor. It never re-reads T0 or checks T0's ID, `used_at`, `reset_email_dispatched_at`, expiry, staleness, or chain deadline after taking the user lock.

A deterministic PostgreSQL 16.15 characterization reproduced the requested T0/T1/T2 interleaving through the production sweeper, issuance service, repositories, and transaction proxies. T1 was dispatch-confirmed before recovery resumed; stale recovery nevertheless set T1 `used_at`, inserted T2, and published T2's email. Two additional deterministic characterizations proved that confirmation or consumption of T0 after selection also fails to stop recovery. Existing PostgreSQL coverage proves that two sweepers selecting T0 each insert and publish a different successor.

## Evidence taxonomy

This report uses four evidence labels deliberately:

- **Source-proved:** follows directly from current Java, annotations, SQL, migrations, and configuration.
- **PostgreSQL-proved:** observed in a deterministic test against the repository's Testcontainers PostgreSQL 16 image using production service/repository paths.
- **Historical:** established by the September 23 investigation, but not relied on as proof of current behavior.
- **Remaining uncertainty:** not executed or not observable from the repository alone.

## Revision and change history

`git branch --show-current` returned `main`; `git rev-parse HEAD` returned `ff91dd1baf6277cbafcebddab352dc261dafc230`.

`git blame` shows that candidate selection and reissuance at `PasswordResetEmailRecoverySweeper.java:76-102` and `PasswordResetTokenService.java:54-68,120-128` retain the September implementation. Post-September changes affecting this area added scheduler isolation/health and stronger lock-contention tests; none added candidate identity to recovery. The latest related commits are scheduler/test changes (`59a14a2` through `f0bd448`), not a candidate-ownership fix.

The worktree contained unrelated untracked user files before this investigation. They were not modified.

## Current lifecycle trace

### Public request

1. `POST /api/v1/auth/password-reset/request` enters `PasswordResetController.request`.
2. The controller calls `PasswordResetService.requestReset(email, clientIp)` and always returns the same 200 response.
3. `PasswordResetService` performs Redis-backed admission before database issuance. If admitted and `UserRepository.findByEmail` finds a user, it calls `issueAndDispatch(user)`.
4. `issueAndDispatch` invokes the distinct proxied bean `PasswordResetTokenService.issue(user, Instant.now())`.
5. `issue` starts a Spring transaction and delegates to the private issuance method:
   - detach the caller's possibly OSIV-managed `User`;
   - `UserRepository.lockForUpdate(userId)`;
   - `PasswordResetTokenRepository.invalidateAllForUser(userId, now)`;
   - generate a raw random token, persist only its hash, and `saveAndFlush` the new row.
6. The transaction proxy commits before control returns to `PasswordResetService`.
7. `PasswordResetService.dispatch` publishes a `PASSWORD_RESET` RabbitMQ message whose idempotency key is the new token UUID.

There is no separate public resend endpoint. Another request to `/request`, subject to the same cooldown/rate limits and enumeration-resistant response, is the resend behavior.

### Dispatch and confirmation

1. `EmailDispatchPublisher.publish` serializes the message and calls `RabbitTemplate.convertAndSend`. Publication exceptions are logged and swallowed.
2. `EmailDispatchConsumer.onMessage` deserializes the message and performs provider HTTP work outside a database transaction.
3. A provider `SENT` response means the provider accepted a new request. `DUPLICATE` means the provider rejected another request under the same idempotency key. Neither proves delivery to the recipient's inbox.
4. For `PASSWORD_RESET`, the consumer executes `claimResetEmailDispatch(tokenId, Instant.now())` in a short `TransactionTemplate` transaction.
5. That update records `reset_email_dispatched_at` if it was null. It does not require `used_at IS NULL`.

Thus “dispatch-confirmed” in this project means provider acceptance/deduplication was observed and the token row was updated. It is not mailbox-delivery proof.

### Consumption

1. `POST /api/v1/auth/password-reset/confirm` hashes the new password before opening a database transaction.
2. `PasswordResetTokenService.consumeAndResetPassword` hashes the raw token, loads its row, detaches its eagerly loaded user, and locks the user first.
3. It conditionally consumes the exact hash only when unused and unexpired.
4. A PENDING user is atomically activated, and all remaining verification/reset tokens are invalidated. An ACTIVE user's password alone is changed.
5. All refresh sessions are revoked in the transaction.
6. After commit, `PasswordResetService` publishes the password-changed notification.

### Recovery

1. `PasswordResetEmailRecoverySweeper.sweepOnce` captures `now` and `threshold = now - grace` outside a transaction.
2. It invokes the derived finder for rows that are dispatch-unconfirmed, unused, unexpired, and older than the threshold, limited by `batchSize`. The query neither locks rows nor orders the batch.
3. For every returned entity, the sweeper evaluates the max window using the snapshot's `firstRequestedAt`.
4. If exhausted, `giveUpOnRecovery(candidateId, now)` conditionally marks that exact row used. This path is already candidate-specific.
5. Otherwise, it calls `PasswordResetService.issueAndDispatchForRecovery(candidate.getUser(), candidate.getFirstRequestedAt())`.
6. Recovery issuance uses the same private method as a user request: it locks the user, invalidates every unused reset token for that user, inserts a fresh row carrying the stale snapshot's `firstRequestedAt`, commits, and publishes.

The identity selected in step 2 is discarded on the non-exhausted path.

### Cleanup

`PasswordResetTokenCleanupTask` deletes rows with `expires_at < now - retention` in a transaction. Recovery selects only unexpired rows, so the intended candidate sets do not overlap. The cleanup query does not acquire a user lock, but it also never requests one after touching a token row; it does not introduce a token-to-user lock cycle.

## Current SQL and transaction boundaries

The relevant exact repository SQL is:

```sql
-- Ordinary and current recovery issuance
UPDATE password_reset_tokens
SET used_at = :now
WHERE user_id = :userId
  AND used_at IS NULL;

-- Consumption
UPDATE password_reset_tokens
SET used_at = :now
WHERE token_hash = :tokenHash
  AND used_at IS NULL
  AND expires_at > :now;

-- Dispatch confirmation
UPDATE password_reset_tokens
SET reset_email_dispatched_at = :now
WHERE id = :tokenId
  AND reset_email_dispatched_at IS NULL;

-- Exhaustion give-up
UPDATE password_reset_tokens
SET used_at = :now
WHERE id = :tokenId
  AND used_at IS NULL
  AND reset_email_dispatched_at IS NULL;
```

Hibernate emitted the following candidate finder during the characterization:

```sql
select ...
from password_reset_tokens prt
where prt.reset_email_dispatched_at is null
  and prt.used_at is null
  and prt.expires_at > ?
  and prt.updated_at < ?
fetch first ? rows only
```

It emitted the user lock as:

```sql
select ... from users where id = ? for no key update
```

The name `lockForUpdate` maps through Hibernate/PostgreSQL to `FOR NO KEY UPDATE`; this is a pessimistic write lock sufficient to serialize the same user's issuance/consumption operations while permitting compatible FK key-share behavior.

Transaction boundaries:

- request rate limiting and user lookup: no encompassing transaction;
- `issue`, current `reissueForRecovery`, `giveUpOnRecovery`, and `consumeAndResetPassword`: separate proxied Spring transactions;
- Rabbit publication: after the issuance/consumption transaction returns and commits;
- provider HTTP send: no database transaction;
- dispatch confirmation: its own short `TransactionTemplate` transaction;
- candidate scan and sweep loop: no transaction.

The characterization asserted `SHOW transaction_isolation = 'read committed'`.

## Locks and ordering

Current token issuance and consumption use User-before-token ordering:

1. pessimistic lock on `users(id)`;
2. token update(s)/insert;
3. refresh-token updates when consuming.

Email verification activation follows the same user-first discipline before cross-token invalidation. The October PostgreSQL lock tests prove the ordinary and recovery issuance paths queue on the exact user row using distinct database backends.

This ordering serializes whole issuance transactions, but serialization alone does not establish *which observed candidate authorized recovery*. A recovery transaction that runs after a fresh request simply invalidates the fresh request's token.

Dispatch confirmation touches only a token row. It can race with candidate selection and current recovery because recovery never locks or rechecks that candidate row.

## Schema, constraints, and indexes

`V7__add_password_reset_tokens.sql` provides:

- primary key on `id`;
- foreign key `user_id -> users(id)`;
- unique constraint on `token_hash`;
- ordinary index on `user_id`;
- partial recovery index on `(expires_at, updated_at)` where `reset_email_dispatched_at IS NULL AND used_at IS NULL`.

`V8__add_first_requested_at_to_password_reset_tokens.sql` adds non-null `first_requested_at`, backfilled from `created_at`.

There is no unique “one live reset token per user” constraint, candidate claim column, recovery generation, or chain ID. None is required for the recommended fix: current user-row serialization plus an exact, locked candidate recheck can enforce ownership with the existing model. A partial unique index would not by itself stop a stale sweeper from invalidating the newer row.

## Deterministic reproduction

### Harness

The investigation added the isolated class:

`src/test/java/com/example/relay/user/recovery/characterization/PasswordResetRecoveryRaceCharacterizationPostgresTest.java`

It is explicitly documented as asserting unsafe current behavior and must be replaced by safety assertions during implementation. It disables background scheduling/listeners, intercepts only the candidate repository boundary, and uses two latches:

- `selected`: released only after the real derived repository query returns T0;
- `release`: prevents the sweeper from entering recovery until the competing operation has committed.

There are no arbitrary sleeps. All issuance, invalidation, dispatch-confirmation, consumption, and durable assertions use production services/repositories and real PostgreSQL transactions. The publisher is spied only to capture post-commit publication without requiring RabbitMQ/provider delivery.

### Primary T0/T1/T2 result

Command:

```bash
./mvnw -Dtest=PasswordResetRecoveryRaceCharacterizationPostgresTest test
```

Result on 2026-10-08: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.

The primary test established:

1. T0 was old, unused, unexpired, and dispatch-unconfirmed.
2. The real PostgreSQL candidate query returned T0; the sweeper paused after return.
3. `PasswordResetService.issueAndDispatch` ran its real proxied transaction, invalidated T0, and created T1.
4. The production `claimResetEmailDispatch` SQL set T1's `reset_email_dispatched_at` in a separate committed transaction.
5. Recovery resumed and called current `reissueForRecovery` with T0's user/timestamp snapshot.
6. Recovery locked the user, invalidated T1, inserted T2, committed, and published T2.

Durable outcome: three rows; T0 used, T1 both dispatch-confirmed and used, T2 alone usable. Publication outcome: one captured user-request publication for T1 and one recovery publication for T2. This is the historical defect on current HEAD.

### Other required interleavings

| Case | Evidence | Current durable/publication outcome |
|---|---|---|
| Two sweepers select T0 | Existing `concurrentRecoveryCallersPreserveOneUsableTokenAndPersistedRequestTime`; PostgreSQL test rerun passed | T0 plus two successors; one successor is invalidated by the second recovery; two distinct emails are published. |
| T0 dispatch confirmation after selection | New deterministic characterization | T0 becomes dispatch-confirmed, then stale recovery marks it used, inserts a successor, and publishes another email. |
| T0 consumed after selection | New deterministic characterization using `consumeAndResetPassword` | Consumption commits, but stale recovery still inserts and publishes a successor. |
| Newer unconfirmed user request | Source-proved and subsumed by the primary test before its confirmation step; existing ordinary-request/recovery test also creates both successors | T1 is invalidated and T2 issued if stale recovery runs second. Confirmation is not required for the defect. |
| Exhaustion | Existing `tokenPastMaxRecoveryWindow_isGivenUpOn_withNoSuccessorRowReissued`; rerun passed | Exact exhausted row is retired; no successor. Because `giveUpOn` is ID-specific, a superseded/confirmed candidate produces a safe zero-row update. |
| Rollback during recovery | Source-proved, not independently forced in PostgreSQL during this investigation | Current invalidation and insert are in one `@Transactional` method; a thrown persistence error rolls both back. Publisher is called only after the transaction proxy returns. A dedicated future regression is still required. |

The selected supporting command ran three existing integration cases (two-sweeper behavior, exhaustion, and Rabbit dispatch confirmation) with `Tests run: 3, Failures: 0, Errors: 0` and `BUILD SUCCESS`.

## Root cause

The root cause is a lost ownership token between observation and mutation:

```text
scan returns {T0 id, user, firstRequestedAt}
                         |
                         | T0 id is discarded
                         v
recovery(user, firstRequestedAt)
                         |
                         v
lock user -> invalidate ALL unused tokens -> insert successor
```

The user lock answers “which issuance runs first?” It does not answer “does T0 still authorize recovery?” Because the transaction never revalidates T0, any later live token can become the target of the blanket invalidation.

The current two-sweeper test encodes the defect as acceptable (“both recovery calls persist successor rows” and two publications). It proves serialization/liveness, not correct candidate ownership, and must be changed during implementation.

## Existing regression coverage relevant to implementation

- `PasswordResetTokenRepositoryTest`: exact conditional updates, finder exclusions, give-up behavior.
- `PasswordResetTokenServiceTest`: issue/reissue, user-before-token order, consumption semantics.
- `PasswordResetServiceTest`: request/dispatch and password-changed publication behavior.
- `PasswordResetEmailRecoverySweeperTest`: candidate routing and exhaustion behavior.
- `PasswordResetEmailRecoverySweeperIntegrationTest`: successful recovery, exhaustion, expiry, Rabbit/provider-confirmation integration.
- `PasswordResetMaintenanceConcurrencyPostgresTest`: multi-sweeper, request/recovery lock contention, cleanup concurrency.
- `PasswordResetConcurrentRequestPostgresTest`: concurrent ordinary issuance.
- `PasswordResetConcurrentConfirmPostgresTest`: single-use consumption.
- `PasswordResetTransactionRollbackIntegrationTest`: consumption rollback.
- `EmailDispatchIntegrationTest`: provider-send result to token-row dispatch confirmation.
- user activation/email verification concurrency suites: cross-token lock-order and PENDING activation guarantees.

## Source, test, historical, and unknown summary

### Current source proves

- non-exhausted recovery discards candidate ID;
- recovery locks the user then invalidates every unused reset token;
- scan and recovery are separate transactions with no candidate lock/claim;
- provider send precedes a separate dispatch-confirmation transaction;
- publication follows successful issuance transaction return;
- chain age is preserved across recovery rows;
- exhaustion give-up is already candidate-specific.

### PostgreSQL tests prove

- the requested stale T0/newer confirmed T1 race exists on current HEAD;
- selected-candidate confirmation and consumption do not stop current recovery;
- two sweepers can issue and publish twice for one selected candidate;
- exhaustion produces no successor in the existing baseline;
- runtime isolation is READ COMMITTED and issuance contends on the user row.

### Historical investigation established

- the same T0/T1/T2 result on September 23;
- the characterization represented database dispatch confirmation, not inbox delivery;
- singleton scheduling was insufficient because an ordinary request is a competing writer.

### Remaining uncertainties

- Real provider/inbox delivery was deliberately not attempted; it is outside the durable invariant and cannot be inferred from `reset_email_dispatched_at`.
- A forced PostgreSQL failure between candidate retirement and successor insert was not added to the investigation harness; atomicity follows from the current transaction shape but needs a permanent implementation regression.
- Boundary timing exactly at grace, expiry, and max-window instants needs pinning in the implementation tests. The design specifies strict comparisons matching current behavior.
- The unlocked batch has no order. This is a fairness/operability concern, not the ownership defect; changing ordering is outside this project unless a test demonstrates starvation.

## Investigation disposition

The defect is confirmed and a design is required. See `docs/superpowers/specs/2026-10-08-candidate-owned-password-reset-recovery-design.md`.

## Implementation verification (2026-10-08)

**Implementation commit:** `31caf0670eb7c703b826c0461f0d10eb61d378ad` (`test: harden password reset race cleanup`). A follow-up formatting-only commit, `3e9ce23` (`style: format candidate-owned recovery tests`), corrected three new test classes flagged by Spotless. The final regression gates below were rerun at `3e9ce23`; the Task 5 documentation commit follows it.

The historical T0/T1/T2 reproduction is now a permanent safety regression: `PasswordResetRecoveryOwnershipPostgresTest.staleSelectedCandidateCannotInvalidateNewerDispatchConfirmedUserRequest` passes with T0 retired, T1 still dispatch-confirmed and usable, no T2, and only T1's publication. The recovery transaction uses the scanned candidate ID and observed user ID, locks User before the exact token row, and evaluates eligibility from the locked row at one post-lock decision time.

Fresh Maven regression gates, each run with `JWT_SECRET=test-only-jwt-secret-for-recovery-tests-32bytes`, passed at the validated HEAD:

| Gate | Exact command | Result |
|---|---|---|
| Focused repository/service/recovery | `./mvnw -Dtest=PasswordResetTokenRepositoryTest,PasswordResetTokenCandidateLockPostgresTest,PasswordResetTokenServiceTest,PasswordResetRecoveryRollbackPostgresTest,PasswordResetServiceTest,PasswordResetEmailRecoverySweeperTest,PasswordResetEmailRecoverySweeperIntegrationTest,PasswordResetRecoveryOwnershipPostgresTest,PasswordResetMaintenanceConcurrencyPostgresTest test` | Exit 0; 53 tests, 0 failures, 0 errors, 0 skipped. |
| Adjacent auth and dispatch | `./mvnw -Dtest=PasswordResetConcurrentRequestPostgresTest,PasswordResetConcurrentConfirmPostgresTest,PasswordResetTransactionRollbackIntegrationTest,PasswordResetActivatesPendingAccountIntegrationTest,EmailDispatchIntegrationTest,EmailVerificationConcurrentVerifyPostgresTest,EmailVerificationConcurrentResendPostgresTest test` | Exit 0; 10 tests, 0 failures, 0 errors, 0 skipped. |
| Unit tests | `./mvnw test -DexcludedGroups=integration` | Exit 0; 573 tests, 0 failures, 0 errors, 0 skipped. |
| Package/compile | `./mvnw -DskipTests package` | Exit 0; production and test sources compiled and package created. |

Spotless requires a baseline qualification. The normal `./mvnw spotless:check` exited 1. Cache-free checks ran in detached worktrees with empty `target/spotless-index` files: base `main` at `ff91dd1baf6277cbafcebddab352dc261dafc230` exited 1 with 190 violating files; the unformatted implementation head exited 1 with 192, including three newly added test classes that needed formatting. Spotless was then applied only to those three test classes and committed in `3e9ce23`. A final cache-free comparison at base `ff91dd1baf6277cbafcebddab352dc261dafc230` and validated HEAD `3e9ce23` still exited 1 on both sides: base had 190 violating files and HEAD had 189. The post-format file-set comparison had one base-only path (`PasswordResetEmailRecoverySweeper.java`) and no head-only paths. The normal repository-wide Spotless gate remains red because the base already fails; no introduced formatting violation remains. No production code or unrelated tests were reformatted, and this is not a repository-wide green claim.

No database migration was added. No schema, configuration, or API contract was changed. Provider acceptance or idempotent duplicate acceptance still does not prove email delivery to a recipient's inbox; `reset_email_dispatched_at` remains evidence of provider-side dispatch handling only.

**Deployment restriction:** Old and new password-reset recovery schedulers must not execute concurrently. Stop or disable recovery scheduling on every old instance and wait until every in-flight old recovery execution has drained or been terminated before enabling any new recovery scheduler. Ordinary request traffic may continue while recovery scheduling is disabled.

### Whole-change adversarial review

- Candidate identity remains explicit from scan through mutation: recovery receives the candidate ID and observed user ID, locks that user first and then the exact candidate, verifies ownership, and never falls back to a user-wide invalidation in the recovery path.
- Issuance and consumption retain User-before-token ordering. Tests cover a request winning after scan, recovery winning while the request blocks on the user row, dispatch confirmation winning before the candidate lock, and confirmation blocking behind recovery's candidate lock. The selected-candidate consumption race is also covered.
- Confirmation and recovery are serialized on the candidate row. A two-sweeper caller test still observes two valid emails only in the distinct ordering where recovery commits first and a later ordinary request then replaces its successor; same-candidate recovery ownership allows only one recovery successor/publication. The `publications.hasSize(2)` assertion in `recoveryWinsThenOrdinaryRequestWaitsOnItsUserLock` is therefore intentional and is not a duplicate-recovery expectation.
- Deadline carry-forward uses the locked candidate's `first_requested_at`; strict expiry, stale grace, and max-window boundaries are covered by deterministic service tests. Exhaustion retires only the exact candidate.
- The forced PostgreSQL successor-persistence failure rolls back candidate retirement and emits no reset publication. Successful publication is reached only after the candidate-owned transaction returns successfully.
- The strengthened mixed-version scheduler restriction above is present. Existing migrations stop at V15; no new migration, scheduler architecture, configuration, API, or unrelated code change was introduced.

Remaining concern: repository-wide Spotless is still red because the base revision independently has 190 violations. The three new test-only formatting issues introduced by this implementation were corrected; no other failure was observed in the requested gates.
