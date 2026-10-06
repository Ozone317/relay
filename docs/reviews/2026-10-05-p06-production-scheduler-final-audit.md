# P06 production scheduler final audit

**Audit date:** 2026-10-06
**Audited implementation revision:** `f0bd44847baf37b3ae14ccdfd8ab84c701d9ee2c` (Tasks 1–5)
**Scope:** Task 6 of `docs/superpowers/plans/2026-10-05-p06-production-scheduler-ownership-and-progress-isolation.md`

## Result

The fresh source inventory resolves to five annotation-driven recurring callbacks, four context-owned
`ThreadPoolTaskScheduler` beans, and a threadless fail-fast bean named `taskScheduler`. Every recurring callback names
one of the approved three business schedulers and calls `ScheduledCallbackRunner`. The webhook deadline scheduler is
separate and demand-driven. No production static/global scheduler, unqualified recurring callback, programmatic
recurring registration, or third-party recurring scheduler was found.

No P06 stop condition appeared. The password-reset concurrency tests show the approved class-B result: duplicate
recovery work/publications can occur, while one usable token remains and token invalidation and authorization state
remain correct. The audit does not find a reason for scheduler leadership or business-semantic changes.

## Fresh source inventory and runtime routing

The prescribed source scan was rerun after the unqualified-callback mutation was removed:

```text
rg -n --hidden --glob '!graphify-out/**' --glob '!.git/**' \
  '@Scheduled|TaskScheduler|SchedulingConfigurer|ScheduledTaskRegistrar|ScheduledExecutorService|scheduleAtFixedRate|scheduleWithFixedDelay|\.schedule\(|@EnableScheduling|TaskExecutor|ExecutorService|Executors\.|ApplicationRunner|CommandLineRunner|SmartLifecycle|@PostConstruct' \
  src/main/java src/main/resources pom.xml
65 matching source lines; the only production @Scheduled owners are the five listed below.
```

| Callback | Fixed-delay property and current default | Scheduler / pool | Metric job | Routing and body notes |
|---|---|---|---|---|
| `RetryScheduler.scheduledReleaseDueRetries` | `relay.retry.scheduler-interval`, `1s` | `deliveryProgressTaskScheduler` / 2 | `retry-promotion` | Admission, then metrics, existing enable check, and retry promotion. |
| `ReadyWorkDispatcher.scheduledDispatch` | `relay.retry.dispatcher-interval`, `1s` | `deliveryProgressTaskScheduler` / 2 | `ready-dispatch` | Admission, then metrics, existing enable check, and dispatch. Confirm continuations remain on the separate bounded executor. |
| `ReconciliationSweeper.scheduledSweep` | `relay.reconciliation.interval`, `30s` | `deliveryReconciliationTaskScheduler` / 1 | `delivery-reconciliation` | One synchronous callback still performs stale-execution recovery followed by dead-letter recovery/publication. |
| `PasswordResetEmailRecoverySweeper.scheduledSweep` | `relay.password-reset.email-recovery.interval`, `60s` | `passwordResetMaintenanceTaskScheduler` / 2 | `password-reset-email-recovery` | One recovery body; no new enable property. |
| `PasswordResetTokenCleanupTask.scheduledCleanup` | `relay.password-reset.cleanup.interval`, `1d` | `passwordResetMaintenanceTaskScheduler` / 2 | `password-reset-token-cleanup` | `TransactionTemplate` starts only inside the admitted body; direct-call cleanup remains transactional. |

The annotation inventory and `ProductionSchedulerTopologyTest` establish fixed delay only, no explicit initial delay,
nonblank qualifiers, exact route/property pairs, and runner dependency on all five production owners. A mutation test
with an extra unqualified annotated method failed the production inventory assertion. With that method present, the
real P00 scheduling opt-in context failed refresh through `UnclassifiedTaskScheduler` with
`Every production @Scheduled method must declare an approved scheduler`. After removing the mutation, the prescribed
source scan again returned 65 matches and the topology/P00 guard selection passed (13 tests, 0 failures/errors).

The two deliberate mutation commands and their expected failures were:

```bash
./mvnw test '-Dtest=BackgroundExecutionContextPolicyTest$OrdinaryContext'
# 2 tests; 1 inventory assertion failure naming task6UnqualifiedMutationProbe.
./mvnw test '-Dtest=BackgroundExecutionContextPolicyTest$ProductionSchedulingOptInContext'
# 1 test; 1 context error caused by the guard's stable classification marker.
```

The real scheduler topology and lifecycle flags are code-owned: `deliveryProgressTaskScheduler` uses pool 2 and prefix
`relay-delivery-progress-`; `deliveryReconciliationTaskScheduler` uses pool 1 and prefix
`relay-delivery-reconciliation-`; `passwordResetMaintenanceTaskScheduler` uses pool 2 and prefix
`relay-password-reset-maintenance-`; `webhookDeadlineTaskScheduler` uses pool 1 and prefix
`relay-webhook-deadline-`. All four set daemon threads, remove-on-cancel, no acceptance after context close, no wait for
tasks on shutdown, no delayed or periodic continuation after shutdown, and a five-second await. Each has the shared
logging/counting error handler. The named `taskScheduler` guard owns no executor or thread and throws from every
schedule method.

### Other production facilities found

| Facility | Ownership and purpose | Audit classification |
|---|---|---|
| `webhookDeadlineTaskScheduler` | Context-owned single-thread scheduler. The HTTP transport schedules one cancellation for an active delivery, retains that exact `ScheduledFuture`, and cancels it in `finally`; it is outside the recurring-business admission gate. | Demand-driven deadline work, not a recurring callback. Existing 15-second monotonic deadline remains authoritative. |
| `readyWorkConfirmationExecutor` | `ThreadPoolTaskExecutor`, core 2/max 4, queue 1,000, prefix `relay-ready-confirm-`, five-second await. | Existing post-publication confirmation continuations; not scheduler invocation capacity. |
| `virtualThreadExecutor` | Spring-owned virtual-thread-per-delivery `ExecutorService`. | Rabbit delivery processing, not scheduled work. |
| `deliveryDnsExecutor` | Fixed pool from `relay.delivery.dns.max-concurrency` (default 40), bounded queue from `queue-capacity` (default 40), daemon threads, abort rejection, `shutdownNow` at close. | Demand-driven DNS resolution, independently bounded. |
| Apache HTTP connection evictor | `evictExpiredConnections()` and `evictIdleConnections(1m)` are enabled on the context-owned closeable Apache client. | Third-party client lifecycle maintenance; the client bean closes with the context. |
| `ScheduledStatusConstraintGuard` | Docker-profile `ApplicationRunner` reads the attempts status constraint once at startup. | One-time startup guard; creates no recurring work. |
| Property `@PostConstruct` methods | Retry, reconciliation, recovery, authentication, mail, and DNS property validation. | Startup validation only; none creates recurring work. |

The expanded executor/scheduling search found no other production scheduler or recurring loop. There is no production
`SchedulingConfigurer`, `ScheduledTaskRegistrar`, `scheduleAtFixedRate`, `scheduleWithFixedDelay`, static/global
scheduled executor, Quartz/ShedLock dependency, or `spring.task.scheduling.*` override. The 65 prescribed scan matches
include imports, executor injection, the six guard methods, the one deadline scheduling call, and the one-time runner;
they are all accounted for by this inventory.

The expanded search command was:

```bash
rg -n --hidden --glob '!graphify-out/**' --glob '!.git/**' \
  'ThreadPool|Executor|ForkJoin|CompletableFuture|new Thread|@Async|@EnableAsync|TaskExecutor|TaskScheduler|Scheduled|@EventListener|ApplicationListener|Timer|Quartz|ShedLock|schedule\(' \
  src/main/java src/main/resources pom.xml
```

## Admission, P00, lifecycle, and metrics evidence

`ScheduledCallbackAdmission` retains its owning `ApplicationContext`, listens at `Ordered.HIGHEST_PRECEDENCE`, ignores
close events from other contexts, and serializes admission and close on one monitor. The admission tests prove both
linearization orders and parent/child event isolation. `ScheduledCallbackRunner` places admission before metrics and
business work. A denied wrapper records only `relay.scheduler.admission.denied{job}`; admitted disabled callbacks still
record their successful no-op execution.

`BackgroundExecutionContextPolicyTest` proves an ordinary Spring test context lacks Spring's scheduled annotation
processor and has zero registered tasks. Its scheduling opt-in full application context registers exactly five
production methods with their five declared qualifiers and the four real scheduler beans. P00's context identity and
`AFTER_CLASS` cleanup remain in place. `BackgroundExecutionCrossContextRegressionTest` proves post-close executor
termination, no live first-context scheduler thread, and distinct executors in the next context. The lifecycle tests
separately prove ordinary early shutdown, queued-wrapper denial at the Relay admission boundary, destruction-time
`shutdownNow`, bounded waiting, and rejection after close.

Metrics are restricted to five `ScheduledJob` values (`retry-promotion`, `ready-dispatch`, `delivery-reconciliation`,
`password-reset-email-recovery`, and `password-reset-token-cleanup`), four scheduler names, and the fixed callback
outcomes `success`/`failure`. The runtime meters are `relay.scheduler.callback.duration{job,outcome}`,
`relay.scheduler.invocation.lag{job}`, `relay.scheduler.admission.denied{job}`, and
`relay.scheduler.errors{scheduler}`. The three recurring scheduler beans expose Boot's `executor.active`,
`executor.queued`, and `executor.pool.size` meters. The metric tests exercise denied callbacks, errors, and fixed
tag-key cardinality. No customer or entity identifiers are tags.

Review correction: the active-admission count is balanced under the gate monitor for accounting only; close does not
inspect it or wait on it.

## Configuration, validation, and cardinality

Scheduler pool sizes and lifecycle flags are hard-coded invariants; there is no operator property that can reduce the
capacity guarantee. The five business cadences and existing business values remain properties. Retry properties check
positive intervals/batch sizes, finite nonnegative jitter, and positive whole-millisecond confirmation timeout below
the unconfirmed-ready grace. Reconciliation validates dead-letter grace against its interval. Reset email recovery
validates grace against interval and requires the maximum recovery window to exceed grace plus interval. Cleanup
interval and retention retain their current defaults (`1d` and `7d`) without a dedicated positivity validator. P06
adds no per-job switch and changes none of these values.

The two fixed-delay members in each two-thread domain cannot overlap themselves, so one blocked member leaves the
other slot available. The focused isolation tests also saturate both slots, observe a queued third task, and release
the blockers. The webhook delayed queue is demand-bounded by active deliveries; explicit future cancellation plus
remove-on-cancel removes completed requests promptly.

## Multi-instance state evidence

| Claim | Existing/fresh test evidence | Finding |
|---|---|---|
| Retry promotions partition due rows | `RetrySchedulerPostgresTest.concurrentSchedulersPromoteDisjointBatches` | PostgreSQL row locks/`SKIP LOCKED` remain authoritative. |
| Ready dispatch claims are partitioned and fenced | `ReadyWorkRepositoryPostgresTest` lock-skip, claim UUID, and old-confirm cases | Scheduler invocation does not replace claim ownership. |
| Stale recovery and dead-letter touch have one winner | `ReconciliationSweeperIntegrationTest`, `AttemptExecutionFencingIntegrationTest` | Generation/age predicates and affected-row checks remain authoritative. |
| Allocation/replay preserve P05 lock and sequence invariants | `AttemptAllocationRepositoryPostgresTest`, `AttemptReplayConcurrencyPostgresTest`, `DeliveryReplayConcurrencyPostgresTest` | Endpoint-before-Delivery lock order and sequence ownership remain unchanged. |
| Two recovery callers and cleanup preserve reset state | `PasswordResetMaintenanceConcurrencyPostgresTest` | Two recovery publications may occur, but there is one unused token, superseded rows are invalidated, persisted `firstRequestedAt` is preserved at PostgreSQL precision, and password/verification state is unchanged. Concurrent cleanup is safe. |
| Ordinary reset racing recovery is safe | Same password-reset PostgreSQL test, with captured backend PIDs observed blocked on the intended user-row lock | Exactly one token remains usable; stale/superseded tokens are invalidated; both legitimate issuance publications remain possible. |

The reset-recovery result remains class B: duplicate work/publication is a known product-level tradeoff. No
simultaneous usable tokens, incorrect invalidation, authorization-state corruption, or other Task 5 stop condition
appeared. No scheduler leadership was introduced or justified.

## Challenge against all 17 P06 invariants

| # | Result and evidence |
|---:|---|
| 1 | Pass: exact five-route inventory; all qualifiers are approved. |
| 2 | Pass: inventory catches an extra callback; real opt-in refresh reaches the fail-fast default. |
| 3 | Pass: `ScheduledProgressIsolationTest` shows reset maintenance cannot occupy delivery progress. |
| 4 | Pass: each delivery-progress member can block without preventing the other from invoking. |
| 5 | Pass: reconciliation and reset maintenance retain independent invocation capacity. |
| 6 | Limitation retained: scheduler isolation does not remove PostgreSQL, RabbitMQ, HTTP, or host contention. |
| 7 | Pass: fixed-delay annotations/defaults, retry tiers, batch/grace/retention values, and enable defaults are unchanged. |
| 8 | Pass: ordinary P00 contexts have zero scheduled registrations; opt-in context has exactly five real callbacks. |
| 9 | Pass: P04 execution ownership and P05 allocation controls remain the database authorities; associated suites pass. |
| 10 | Pass: source and concurrency evidence show no deployment-wide leader requirement or scheduler lock. |
| 11 | Pass: close event admission ordering, post-close rejection, interruption, bounded await, and termination are tested. |
| 12 | Pass: tags are closed sets; denied work emits only the denial counter. |
| 13 | Pass: 65 prescribed inventory matches and expanded executor/third-party search accounted for above. |
| 14 | Pass: reconciliation remains one synchronous two-phase callback; no per-phase isolation claim is made. |
| 15 | Pass: transport retains and cancels the returned deadline future in `finally`; lifecycle/deadline tests pass. |
| 16 | Pass: all five recurring entries use the runner; webhook deadlines bypass recurring admission. |
| 17 | Pass: denied scheduled cleanup starts no transaction or repository work; admitted and direct-call cleanup remain atomic. |

## Verification record

All commands used a synthetic test-only `JWT_SECRET`; email tests used inert test sender values. The results below are
fresh for this audit.

| Gate | Command/selector | Result |
|---|---|---|
| Task 6 focused P06 repeat | `./mvnw -q test -Dtest=ProductionSchedulerTopologyTest,ScheduledProgressIsolationTest,ScheduledJobMetricsTest,ProductionSchedulerLifecycleTest,BackgroundExecutionContextPolicyTest,BackgroundExecutionCrossContextRegressionTest,PasswordResetMaintenanceConcurrencyPostgresTest,ScheduledLoopEnabledSmokeTest`, looped for runs 1–3 | Each run: 46 tests, 0 failures/errors/skips; 138 total executions. |
| P00 | `./mvnw test -Dtest=BackgroundExecutionContextPolicyTest,BackgroundExecutionCrossContextRegressionTest,ScheduledLoopEnabledSmokeTest` | 14 tests, 0 failures/errors/skips. |
| P03 focused safety | `./mvnw test -Dtest=WebhookUriParserTest,WebhookUrlValidatorTest,PublicDestinationAddressPolicyTest,SpecialPurposeAddressCatalogTest,DeliveryDnsPropertiesBindingTest,DeliveryDnsPropertiesValidationTest,DeliveryDnsPropertiesConfigurationKeysTest,DeliveryDeadlineContextTest,SystemHostAddressLookupTest,PolicyEnforcingDnsResolverTest,DeliveryHttpClientSecurityConfigTest,ApacheDnsSocketBindingIntegrationTest,ApacheWebhookHttpTransportTest,ApacheWebhookHttpTransportDeadlineTest,BoundedApacheResponseBodyConsumerTest,ApacheResponseConsumptionIntegrationTest,WebhookDestinationAdversarialIntegrationTest,WebhookTlsIdentityIntegrationTest` | 140 tests, 0 failures/errors/skips. |
| P03 post-target regressions | `./mvnw test -Dtest=HmacSignerTest,DeliveryWorkerAckLifecycleTest,DeliveryHttpClientPinningTest,DeliveryHttpClientConnectTimeoutTest,AttemptServiceMarkFailedAndCreateRetryAtomicityTest,DeliveryReplayConcurrencyPostgresTest,DeliveryReplayLifecycleIntegrationTest,PasswordResetEmailRecoverySweeperTest,PasswordResetConcurrentRequestPostgresTest,ScheduledLoopGatingTest` | 64 tests, 0 failures/errors/skips. |
| P04 database/migration | `./mvnw test -Dtest=AttemptExecutionRepositoryPostgresTest,AttemptExecutionMigrationLifecyclePostgresTest,RepositoryPostgresAuditTest` | 16 tests, 0 failures/errors/skips. |
| P04 lifecycle/replay/recovery | `./mvnw test -Dtest=AttemptExecutionFencingIntegrationTest,AttemptServiceMarkFailedAndCreateRetryAtomicityTest,DeliveryReplayConcurrencyPostgresTest,DeliveryReplayLifecycleIntegrationTest,ReadyWorkDispatcherIntegrationTest,ReadyWorkDispatcherRecoveryIntegrationTest,ReconciliationSweeperIntegrationTest,DeliveryWorkerScheduledIsolationIntegrationTest` | 55 tests, 0 failures/errors/skips. |
| P05 focused | `./mvnw -Dtest=BackgroundExecutionContextPolicyTest,BackgroundExecutionCrossContextRegressionTest,ApacheResponseConsumptionIntegrationTest,WebhookDestinationAdversarialIntegrationTest,AttemptExecutionFencingIntegrationTest,DeliveryWorkerOwnershipFencingIntegrationTest,AttemptServiceMarkFailedAndCreateRetryAtomicityTest,AttemptSequenceMigrationPostgresTest,AttemptAllocationRepositoryPostgresTest,DeliveryReplayConcurrencyPostgresTest,DeliveryReplayLifecycleIntegrationTest,DeliveryReplayHttpIntegrationTest -Drelay.retry.scheduling-enabled=false test` | 81 tests, 0 failures/errors/skips. |
| P05 supplemental | `./mvnw -Dtest=RepositoryPostgresAuditTest,AttemptExecutionMigrationLifecyclePostgresTest,ReconciliationSweeperIntegrationTest,AttemptAllocationMetricsTest,DeliveryStatusViewPostgresTest,MessageServiceTransactionIntegrationTest,AttemptServiceTest,DeliveryReplayServiceTest -Drelay.retry.scheduling-enabled=false test` | 57 tests, 0 failures/errors/skips. |
| Task 5 multi-instance selector | `./mvnw test -Dtest=PasswordResetMaintenanceConcurrencyPostgresTest,RetrySchedulerPostgresTest,AttemptExecutionFencingIntegrationTest,AttemptExecutionMigrationLifecyclePostgresTest,AttemptExecutionRepositoryPostgresTest,AttemptAllocationRepositoryPostgresTest,AttemptReplayConcurrencyPostgresTest,DeliveryReplayConcurrencyPostgresTest,ReadyWorkRepositoryPostgresTest,ReconciliationSweeperIntegrationTest` | 75 tests, 0 failures/errors/skips. |
| Password-reset focused | `./mvnw test -Dtest=PasswordResetMaintenanceConcurrencyPostgresTest,PasswordResetEmailRecoverySweeperIntegrationTest,PasswordResetEmailRecoverySweeperTest,PasswordResetTokenCleanupTaskTest,PasswordResetConcurrentRequestPostgresTest,PasswordResetConcurrentConfirmPostgresTest,PasswordResetActivatesPendingAccountIntegrationTest,PasswordResetTransactionRollbackIntegrationTest` | 17 tests, 0 failures/errors/skips. |
| Task 4 admission/P00/lifecycle | `./mvnw test -Dtest=ScheduledCallbackAdmissionTest,BackgroundExecutionContextPolicyTest,BackgroundExecutionCrossContextRegressionTest,ProductionSchedulerLifecycleTest,ScheduledLoopEnabledSmokeTest` | 25 tests, 0 failures/errors/skips. |
| Task 1 topology | `./mvnw test -Dtest=ProductionSchedulerTopologyTest,ScheduledProgressIsolationTest,ScheduledLoopEnabledSmokeTest,RetrySchedulerPostgresTest,ReadyWorkDispatcherIntegrationTest,ReconciliationSweeperIntegrationTest,PasswordResetEmailRecoverySweeperIntegrationTest,PasswordResetTokenCleanupTaskTest` | 47 tests, 0 failures/errors/skips. |
| Task 2 metrics | `./mvnw test -Dtest=ScheduledJobMetricsTest,ProductionSchedulerTopologyTest,RetrySchedulerPostgresTest,ReconciliationSweeperIntegrationTest` | 32 tests, 0 failures/errors/skips. |
| Task 3 deadline | `./mvnw test -Dtest=ProductionSchedulerLifecycleTest,ApacheWebhookHttpTransportDeadlineTest,ApacheResponseConsumptionIntegrationTest,ApacheWebhookHttpTransportTest,DeliveryWorkerIntegrationTest,DeliveryReplayLifecycleIntegrationTest` | 53 tests, 0 failures/errors/skips. |
| Final inventory guards | `./mvnw test -Dtest=ProductionSchedulerTopologyTest,BackgroundExecutionContextPolicyTest` | 13 tests, 0 failures/errors/skips after the mutation was removed. |
| Full suite, specified uncapped run | `./mvnw test` | Reproduced the known unrelated hang in `BrevoEmailSenderTest.send_throwsEmailSendException_on429RateLimited`: log records a 429 and automatic retry; `jcmd Thread.print` shows the test thread blocked reading a socket response while MockWebServer waits for a queued response. Interrupted after confirming the exact test/thread; no completion count is claimed for this run. |
| Full suite with known hanging method excluded | `./mvnw test '-Dtest=*,!BrevoEmailSenderTest#send_throwsEmailSendException_on429RateLimited'` | 898 tests, 0 failures/errors/skips; BUILD SUCCESS in 5:57. This is the broad passing result; the one excluded method is explicitly reported. |
| CI global format | `./mvnw spotless:check` | Fails: 192 Java files need formatting. No source was reformatted. The scoped check below covers every P06-touched Java file. |
| P06-touched format | `P06_FILES="$(git diff --name-only 5d4918e..HEAD | rg '\.java$' | paste -sd, -)"; ./mvnw -DspotlessFiles="$P06_FILES" spotless:check` | BUILD SUCCESS; all 35 P06-touched Java files pass. |
| Diff and P04/P05 separation | `git diff --check 5d4918e..HEAD`; `git diff --exit-code 5d4918e..HEAD -- src/main/resources/db/migration src/main/java/com/example/relay/attempt src/main/java/com/example/relay/deliveryengine/retry/RetryProperties.java src/main/java/com/example/relay/deliveryengine/reconciliation/ReconciliationProperties.java src/main/java/com/example/relay/user/recovery/PasswordResetEmailRecoveryProperties.java src/main/java/com/example/relay/user/recovery/PasswordResetTokenCleanupProperties.java` | Both pass. No migration, Attempt/P04 implementation, retry/reconciliation/password-reset property, or business-default files changed. |

The P04 plan's legacy API `rg` uses `markSucceeded\(Attempt`, which also matches the current `AttemptExecution`
method signatures by prefix. The intended boundary-aware check (`markSucceeded\(Attempt\b`, and equivalent failure
methods) returns no legacy API matches. The production `UPDATE attempts` writer list was also inspected; it is confined
to the existing fenced execution, ready-work claim/publication, and reconciliation paths.

The passing suite establishes scheduler callback invocation isolation only. It does not establish that database,
RabbitMQ, HTTP, or shared host contention has been removed.
