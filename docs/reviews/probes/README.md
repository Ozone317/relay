# Second-pass readiness probes

Companion to [Readiness Verification & Engineering Projects](../2026-09-23-readiness-verification-and-projects.md).

`ReadinessProbe.java` is a standalone review harness, outside application sources and the default test suite. This harness and its output below are a **pre-P03 historical snapshot**: its SSRF result records the vulnerability before P03, and Task H removed the JDK delivery client API the harness constructs. It is no longer a current or runnable verification command. Keep invariant-preserving checks in the project regression tests; do not add this harness unchanged to CI. The dated readiness reports preserve their pre-implementation findings and evidence as of their review dates.

## Scope and safety

Requires Java 21, Maven dependencies, and a local Docker engine capable of running PostgreSQL 16. Uses an ephemeral Testcontainers database with the repository's Flyway migrations, synthetic accounts, local HTTP receivers, and mocked email/dead-letter publishers. It does not use a production database, send real email, or contact customer destinations. The minimal Spring context has no component scanning or scheduled-job activation; tested jobs are invoked explicitly. The database container is removed when the run ends.

The harness does write synthetic data to its isolated database and compile classes under `target/readiness-review`. It does not modify production source. The `productionFilesModified=false` output is a descriptive label, not a filesystem assertion; repository changes were separately checked with Git.

Concurrency uses latches and committed operations. The stale-worker case simulates an expired claim through timestamps, rather than inducing a real 90-second pause. The replay gate surrounds the real service's creation call. The reset gate wraps the real candidate query, then runs real token transactions. Email dispatch confirmation is written through the production repository method and checked before recovery resumes; actual provider delivery is not tested.

## Current post-P03 verification

Run date: 2026-09-29. P03 focused checks and the current Apache transport regressions passed. The one full-suite run
reported four lifecycle timing errors. The subsequent bounded causal investigation is preserved in
[P03 full-suite lifecycle-error investigation](2026-09-30-p03-full-suite-lifecycle-investigation.md).

Focused P03 command and result after the interruption-test correction: **140 tests, 0 failures, 0 errors, 0 skipped**.
The exact interruption test also passed six consecutive runs.

```bash
./mvnw test -Dtest=WebhookUriParserTest,WebhookUrlValidatorTest,PublicDestinationAddressPolicyTest,SpecialPurposeAddressCatalogTest,DeliveryDnsPropertiesBindingTest,DeliveryDnsPropertiesValidationTest,DeliveryDnsPropertiesConfigurationKeysTest,DeliveryDeadlineContextTest,SystemHostAddressLookupTest,PolicyEnforcingDnsResolverTest,DeliveryHttpClientSecurityConfigTest,ApacheDnsSocketBindingIntegrationTest,ApacheWebhookHttpTransportTest,ApacheWebhookHttpTransportDeadlineTest,BoundedApacheResponseBodyConsumerTest,ApacheResponseConsumptionIntegrationTest,WebhookDestinationAdversarialIntegrationTest,WebhookTlsIdentityIntegrationTest
```

The adapted post-P01/P02 lifecycle command and result: **87 tests, 0 failures, 0 errors, 0 skipped**. The two JDK
transport tests in the earlier probe command were removed with that transport in Task H; the Apache security, binding,
deadline, and response suites below cover the active production transport.

```bash
./mvnw test -Dtest=HmacSignerTest,DeliveryWorkerAckLifecycleTest,DeliveryHttpClientSecurityConfigTest,ApacheDnsSocketBindingIntegrationTest,ApacheWebhookHttpTransportTest,ApacheWebhookHttpTransportDeadlineTest,BoundedApacheResponseBodyConsumerTest,ApacheResponseConsumptionIntegrationTest,AttemptServiceMarkFailedAndCreateRetryAtomicityTest,DeliveryReplayConcurrencyPostgresTest,DeliveryReplayLifecycleIntegrationTest,PasswordResetEmailRecoverySweeperTest,PasswordResetConcurrentRequestPostgresTest,ScheduledLoopGatingTest
```

The single full-suite command was `./mvnw test`: **768 tests, 0 assertion failures, 4 errors, 0 skipped**. The errors
were:

- `ReconciliationSweeperIntegrationTest.staleInFlightAttempt_isResetWithoutDirectPublication`: the reset row was
  expected to have no ready-dispatch lease, but `ready_dispatch_claim_id` was populated during the assertion window.
- `DeliveryWorkerIntegrationTest.exception_beforeFinalAttempt_marksAttemptFailedAndCreatesRetry`: the retry child was
  expected to remain `SCHEDULED`, but was observed as `CREATED`.
- `DeliveryWorkerIntegrationTest.protectedDestination_isRejectedThroughWorkerLifecycleWithoutOpeningListener`:
  the protected-DNS and mixed-DNS cases each expected a `SCHEDULED` retry child but observed `CREATED`.

The causal follow-up identified the exact writers as background scheduler/dispatcher components in other cached Spring
contexts sharing the same PostgreSQL database. Both transition families reproduced through the same mechanism on
pre-P03 revision `9044e47`. The existing exception-path error is a pre-existing test-isolation race; the two P03-only
DNS cases are timing exposure of that same race rather than P03 transport regressions. No P03 policy, transport, P01,
or P02 assertion failed, and no P03 production change is required. The separately tracked P00 isolation issue can
still make the uncapped full suite nondeterministic.

The forbidden-configuration audit used:

```bash
rg -n "allow-private|NoopHostnameVerifier|TrustAll|useSystemProperties|setProxy\(|setProxySelector|SocksProxy|followRedirect|disableHostnameVerification|dnsjava" src pom.xml
```

Matches were explicit no-SOCKS configuration, negative proxy/SOCKS test assertions, and comments documenting forbidden
system-property behavior. No private-address bypass, permissive TLS, proxy route, redirect following, or dnsjava
dependency was found. The branch scope audit found no schema/migration, Attempt-state, retry-policy, URL-snapshot, or
scheduler production changes. See the tracked P03 security invariants in
[`docs/superpowers/specs/2026-09-24-p03-outbound-destination-safety-design.md`](../../superpowers/specs/2026-09-24-p03-outbound-destination-safety-design.md#19-security-invariants-checklist).

## Historical pre-P03 reproduction

The following output records pre-P03 behavior only. `ReadinessProbe.java` depends on the JDK delivery client removed
in Task H and is no longer a current or runnable verification harness.

## Observed pre-P03 output (historical)

```text
PROBE unicode contentType=application/json utf8Matches=true receiverSignatureMatches=true
PROBE ssrf loopbackDtoAccepted=true actualWorkerReachedLoopback=true
PROBE ascii-control contentType=application/json utf8Matches=true receiverSignatureMatches=true
PROBE large-success http200Calls=2 status=IN_FLIGHT sameAttemptNo=1
PROBE large-failure-control status=FAILED_RETRYING capturedCharacters=10240
PROBE redirect-control followed=false recordedStatus=302
PROBE stale-worker B_SUCCEEDED_overwritten_by_A_FAILED_RETRYING=true scheduledChild=1
PROBE stale-replay attemptNo7Rows=2 newReplayAfterSuccess=true
PROBE stale-reset-recovery freshConfirmedTokenInvalidated=true singletonSweeperPlusUserRequest=true
PROBE scheduler unqualifiedThread=relay-retry-1 qualifiedTickBlockedUntilRelease=true
PROBE COMPLETE allExpectedOutcomesObserved=true productionFilesModified=false
```

## Existing regressions run in the historical pass

Maven exited zero. Fresh Surefire reports from the historical pre-P03 pass contained **23 tests, zero failures, zero errors, zero skipped**, across these ten classes:

| Class | Tests |
|---|---:|
| HmacSignerTest | 4 |
| DeliveryWorkerAckLifecycleTest | 2 |
| DeliveryHttpClientPinningTest | 1 |
| DeliveryHttpClientConnectTimeoutTest | 2 |
| AttemptServiceMarkFailedAndCreateRetryAtomicityTest | 1 |
| DeliveryReplayConcurrencyPostgresTest | 1 |
| DeliveryReplayLifecycleIntegrationTest | 3 |
| PasswordResetEmailRecoverySweeperTest | 3 |
| PasswordResetConcurrentRequestPostgresTest | 1 |
| ScheduledLoopGatingTest | 5 |

These passing controls verify existing protections and expose gaps in their interleaving/receiver coverage. They do not disprove the independently reproduced defects. The full suite, production deployment, actual egress policy, heap-exhaustion behavior, and real load/restore envelope were **not** validated in this pass. The first report's separate 62-test selection is not counted again here. No blanket test parallelism or broker-default changes were introduced.

## P04 execution ownership verification (2026-10-04)

This section is post-P04 evidence. The historical pre-P03 sections above are retained unchanged.

### Fresh verification

Revision audited before documentation: `5e802797f2d07f003b8bfea887aaff326473da7e`.

Focused migration and PostgreSQL query audit:

```bash
JWT_SECRET=0123456789abcdef0123456789abcdef RELAY_EMAIL_SENDER_EMAIL=test@example.com RELAY_EMAIL_SENDER_NAME=RelayTest BREVO_API_KEY=test-api-key ./mvnw test -Dtest=AttemptRepositoryApiTest,AttemptExecutionRepositoryPostgresTest,AttemptExecutionMigrationLifecyclePostgresTest,RepositoryPostgresAuditTest,AttemptRepositoryTest,ReadyWorkRepositoryPostgresTest
```

Result after the single-entry cleanup: **44 tests, 0 failures, 0 errors, 0 skipped**. The first API regression run failed because `AttemptRepository` still exposed the obsolete mutation; after its removal, the combined focused run passed. Flyway validated and applied all 12 migrations from an empty PostgreSQL 16.15 schema through V12. The migration lifecycle test verifies V12 backfills existing `IN_FLIGHT` rows with `updated_at` and PostgreSQL rejects both invalid shapes: `IN_FLIGHT` with `execution_claimed_at IS NULL`, and non-`IN_FLIGHT` with a non-null claim timestamp. Repository PostgreSQL tests execute the hand-written attempt queries, and remaining stale-reset test setup uses the fenced execution repository.

Fresh full suite:

```bash
JWT_SECRET=0123456789abcdef0123456789abcdef RELAY_EMAIL_SENDER_EMAIL=test@example.com RELAY_EMAIL_SENDER_NAME=RelayTest BREVO_API_KEY=test-api-key ./mvnw test
```

Result after removing the duplicate legacy claim API and its direct tests: **806 tests, 0 failures, 0 errors, 0 skipped**; Maven `BUILD SUCCESS`, elapsed 9:12.

### Attempt state-writer inventory

The inventory covers `src/main/java/**`, all migrations containing Attempt DML, the mapped `Attempt` setters, and repository persistence call sites. There are **zero unclassified production writers**. “Can touch `IN_FLIGHT`” below means whether the predicate permits a matching `IN_FLIGHT` row; field-only writes may touch one without altering status or the execution claim.

| Writer / location | Transition or fields | Ownership domain and complete predicate/guard | Can operate on `IN_FLIGHT`? Why it cannot bypass execution fencing or claim-time consistency |
|---|---|---|---|
| `Attempt` constructor → `AttemptService.createFromSubscriptionList` → `attemptRepository.saveAll` (`Attempt.java:127`, `AttemptService.java:39-43,47-57`) | Inserts a new row as `CREATED`; initial generation is 0 and claim time null. | Scheduling / entity creation; insert only, new ID. | No existing row can be touched. It cannot transition an existing attempt or supply a non-null claim time. |
| `AttemptService.createRetry` → `attemptRepository.save` (`AttemptService.java:73-80`) | Inserts a new `SCHEDULED` retry with `next_retry_at`; generation 0, no execution claim. | Scheduling; insert only, new ID and incremented attempt number. | No existing row can be touched; claim consistency holds for the new non-`IN_FLIGHT` row. |
| `AttemptService.createReplay` → `attemptRepository.saveAndFlush` (`AttemptService.java:83-86`) | Inserts a new `CREATED` replay; generation 0, claim time null. | Scheduling / replay creation; insert only, new ID and incremented attempt number. | No existing row can be touched; claim consistency holds for the new row. |
| `Attempt.setStatus` (`Attempt.java:59-62`) and its only production call (`AttemptService.java:77`) | In-memory status assignment to `SCHEDULED` on a newly constructed retry; no direct database write. | Scheduling / entity creation; only called before the new row is inserted. | No persisted in-flight row is loaded and mutated through this setter in production. Existing-row lifecycle changes use guarded SQL. |
| `AttemptExecutionRepositoryImpl.claim` (`AttemptExecutionRepositoryImpl.java:24-38`), called by `AttemptService.claim` | `CREATED → IN_FLIGHT`; increments generation and sets claim time and `updated_at` atomically; returns generation and timestamp for the `AttemptExecution` token. | Execution; predicate `id=:attemptId AND status='CREATED'`. | No; the row-level predicate excludes `IN_FLIGHT`, and the atomic update satisfies the check constraint. This is the sole claim path called by the production worker (`DeliveryWorker`). |
| `AttemptExecutionRepositoryImpl.findStaleInFlight` (`AttemptExecutionRepositoryImpl.java:41-52`) | Read-only selection of `id`, generation, and claim time. | Read-only guard; `status='IN_FLIGHT' AND execution_claimed_at < CURRENT_TIMESTAMP - grace`, ordered by claim time/id and batch limited. | Yes, read-only. It does not mutate ownership. |
| `AttemptExecutionRepositoryImpl.resetStuck` (`AttemptExecutionRepositoryImpl.java:55-69`), called by reconciliation | `IN_FLIGHT → CREATED`; clears execution claim time and ready-publication marker/lease fields; leaves generation unchanged. | Execution / reconciliation; `id`, `status='IN_FLIGHT'`, `execution_generation=:observedGeneration`, and claim time older than grace are all rechecked in the update. | Yes, but only as the sole `IN_FLIGHT → CREATED` mutation and only for the observed positive or legacy generation after grace. It atomically clears claim time, so the consistency constraint remains satisfied; stale generations lose the update. |
| `AttemptExecutionRepositoryImpl.markSucceeded` (`AttemptExecutionRepositoryImpl.java:73-90`) | `IN_FLIGHT → SUCCEEDED`; sets response code/body, latency; clears retry/error and claim time; updates `updated_at`. | Execution completion; `id=:attemptId AND status='IN_FLIGHT' AND execution_generation=:generation AND execution_generation>0`. | Yes, only for the current positive-generation owner. Claim time clears in the same statement as status change. |
| `AttemptExecutionRepositoryImpl.markFailed` (`AttemptExecutionRepositoryImpl.java:93-116`), including service retry-parent completion | `IN_FLIGHT → FAILED_RETRYING` or `DEAD`; records response/error/latency and retry time as applicable; clears claim time. | Execution completion; status parameter is restricted in Java to `FAILED_RETRYING`/`DEAD`; SQL requires matching ID, `IN_FLIGHT`, matching generation, and generation > 0. | Yes, only for the current positive-generation owner. Claim time clears atomically. `markFailedAndCreateRetry` inserts the `SCHEDULED` child only after exactly one parent row was updated in the same transaction. |
| `ReadyWorkRepositoryImpl.promoteDueScheduled` (`ReadyWorkRepositoryImpl.java:21-39`) | `SCHEDULED → CREATED`; clears ready publication/dispatch fields and updates timestamp. | Scheduling; due rows only (`status='SCHEDULED' AND next_retry_at<=CURRENT_TIMESTAMP`), ordered/batched under `FOR UPDATE SKIP LOCKED`; update joins only selected IDs. | No; only selected `SCHEDULED` rows can be changed. It neither writes execution generation nor claim time. |
| `ReadyWorkRepositoryImpl.claimUnpublishedReady` (`ReadyWorkRepositoryImpl.java:43-65`) | Sets ready-dispatch claim ID/time and `updated_at`; status unchanged. | Ready-publication; candidates require `status='CREATED'`, `ready_published_at IS NULL`, and absent/expired ready-dispatch lease; lock/skip-locked and batch limited. | No; predicate excludes `IN_FLIGHT`. It does not write execution claim fields or status. |
| `ReadyWorkRepositoryImpl.markReadyPublished` (`ReadyWorkRepositoryImpl.java:69-81`) | Sets `ready_published_at`; clears ready-dispatch claim ID/time and updates timestamp. | Ready-publication; `id`, `status='CREATED'`, unpublished marker, and matching `ready_dispatch_claim_id`. | No; predicate excludes `IN_FLIGHT`. It does not write execution claim fields or status. |
| `AttemptRepository.touchDeadLetterCandidate` (`AttemptRepository.java:25-34`), called by reconciliation | Field-only `updated_at=:now`. | Notification recovery; matching ID, `status='DEAD'`, no notification timestamp, and `updated_at < threshold`. | No; status must be `DEAD`; no lifecycle or execution-claim field changes. |
| `AttemptRepository.claimDeadLetterNotification` (`AttemptRepository.java:36-43`), called after notification send | Field-only `dead_letter_notified_at=:now`. | Notification; matching ID and `dead_letter_notified_at IS NULL`. | Yes, predicate does not exclude `IN_FLIGHT`; the mutation changes neither status, `execution_generation`, nor `execution_claimed_at`, so it cannot complete, reset, or steal execution ownership and cannot violate the consistency check. |
| `V5__add_deliveries_and_delivery_status_view.sql:27-31` | Migration backfill sets only `attempts.delivery_id` by joining each row to its delivery. | Migration; one-time schema migration, join on message and endpoint IDs. | It can match any status, including `IN_FLIGHT`, but does not touch status or execution claim fields. |
| `V12__add_attempt_execution_fencing.sql:1-13` | Adds generation default 0 and nullable claim time; backfills claim time from `updated_at` for `IN_FLIGHT`; adds nonnegative generation and status/claim consistency checks and stale index. | Migration; one-time DDL/DML; backfill predicate is exactly `status='IN_FLIGHT'`. | Intentionally operates on existing in-flight rows to initialize their claim time. It does not create runtime ownership; it makes claim time non-null before installing the consistency constraint. |
| `AttemptRepository.findByStatusAndNextRetryAtBefore` and `findByStatusAndDeadLetterNotifiedAtIsNullAndUpdatedAtBefore` (`AttemptRepository.java:20-23`), plus delivery/replay status reads | Read-only candidate and guard queries. | Read-only guard; supplied status/threshold/limit; replay checks active statuses before inserting a new row. | Reads do not mutate. Replay creates a new `CREATED` row; unique active-attempt constraint protects concurrent insertions. |

Production mutation inventory conclusion: `AttemptExecutionRepositoryImpl.claim`, called through `AttemptService.claim` and the production worker, is the **sole runtime SQL entry into `IN_FLIGHT`**. It sets status, increments generation, and sets claim time atomically. All authoritative completion SQL is in `AttemptExecutionRepositoryImpl`; both completion shapes require `id + IN_FLIGHT + matching positive generation` and clear claim time. The sole `IN_FLIGHT → CREATED` SQL is generation-scoped stale reconciliation with a second grace check and claim-time clearing. Ready-publication status mutation excludes `IN_FLIGHT`; ready-publication lease/marker and notification SQL never mutate execution status/generation/claim time. The PostgreSQL check constraint rejects each inconsistent shape. The obsolete `AttemptRepository.claim(UUID, Instant)` mutation and its direct tests were removed after audit; claim setup in unrelated repository tests now uses the execution repository.

The task's literal obsolete-API grep was retained:

```bash
rg -n 'markSucceeded\(Attempt[),]|markFailed\(Attempt[),]|markFailedAndCreateRetry\(Attempt[),]|claim\([^)]*,\s*Instant|findByStatusAndUpdatedAtBefore\(AttemptStatus.IN_FLIGHT' src/main
```

Result: **no matches**. `[),]` requires the `Attempt` type name to end at the parameter boundary, avoiding the former false positive where `AttemptExecution` began with that prefix while still detecting bare-`Attempt` completion overloads. The remaining claim has the generation-bearing `AttemptExecutionRepository.claim(UUID)` signature; no timestamp-taking claim API or stale in-flight query remains.

The completion SQL grep was retained:

```bash
rg -n "UPDATE attempts" src/main/java/com/example/relay/attempt
```

It finds nine statements: four execution SQL statements in `AttemptExecutionRepositoryImpl` (claim, stale reset, success, failure), three ready-work statements (promotion, ready lease, publication confirmation), and two `AttemptRepository` statements (dead-letter touch and dead-letter notification claim). Each is individually classified above. Only the success and failure statements are authoritative completion SQL; both have the positive-generation predicate. The reset is the only generation-scoped `IN_FLIGHT → CREATED` statement.

### Deployment order

Mixed-version rolling deployment is unsafe. V12 installs an invariant that old binaries do not maintain, and older workers cannot participate in the generation fencing protocol. Use this exact quiesced order:

1. Quiesce old background writers and allow/stop in-flight worker activity, retry scheduling, ready dispatch, reconciliation, and dead-letter recovery so no old binary continues mutating Attempt rows.
2. Apply V12 to the database while those writers remain quiesced.
3. Deploy only binaries containing the fenced claim, completion, and reconciliation paths; do not leave old and new binaries running together.
4. Resume background writers and workers only after all active binaries are fenced.
5. Observe execution ownership counters, especially revoked/lost-race outcomes, along with in-flight age and delivery health.
