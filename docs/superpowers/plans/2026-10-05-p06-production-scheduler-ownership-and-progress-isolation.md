# P06 — Production Scheduler Ownership and Progress Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** READY TO IMPLEMENT after the 2026-10-06 amendment pass; do not start without explicit approval.

**Goal:** Give every production scheduled callback explicit, lifecycle-safe ownership so unrelated work cannot prevent delivery progress or maintenance invocation.

**Architecture:** Five fixed-delay callbacks route through three explicit `ThreadPoolTaskScheduler` domains sized to their member loops. A threadless fail-fast default rejects unclassified callbacks, the static webhook deadline scheduler becomes context-owned, and P00 continues to suppress scheduled registration centrally.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Framework 6.2.19, Micrometer 1.15.12, JUnit 5, Awaitility, PostgreSQL 16/Testcontainers, RabbitMQ/Testcontainers

**Spec:** `docs/superpowers/specs/2026-10-05-p06-production-scheduler-ownership-and-progress-isolation-design.md`

## Global Constraints

- Preserve retry tiers, cadences, batch/grace values, HTTP/Rabbit semantics, and business enable defaults.
- Preserve P04 generation fencing and P05 Endpoint → Delivery lock order, sequence allocation, V13, and V14.
- Do not add distributed leadership, scheduler locks, `@Primary` routing, bean-order routing, or programmatic recurring registration.
- Every production `@Scheduled` method must have a non-empty documented `scheduler` qualifier.
- Ordinary P00 contexts register zero callbacks; opt-in contexts use the real production topology.
- Pool sizes and lifecycle flags are code-owned invariants, not operator tuning properties.
- Use latches, barriers, captured registration, queue state, and committed state. Bounded awaits are failure limits only.
- Metrics use fixed scheduler/job/outcome tags and no customer or entity identifiers.
- Do not reformat or modify unrelated user files in the dirty worktree.

## Review Focus

- A new unqualified callback fails the inventory test and application startup; Task 1.
- One blocked fixed-delay member cannot consume both slots of a two-thread domain; Task 1.
- Context close cancels queued/future work and releases threads across cached/dirtied contexts; Task 4.
- Concurrent instances preserve P04/P05/ready-work/token invariants; Task 5.
- Completed HTTP work removes its deadline task and context close removes the old static lifecycle leak; Task 3.

---

## File structure

New production files:

- `common/scheduling/SchedulerNames.java`: stable bean/job constants.
- `common/scheduling/ProductionSchedulerConfig.java`: scheduler beans and lifecycle policy.
- `common/scheduling/UnclassifiedTaskScheduler.java`: threadless fail-fast default.
- `common/scheduling/ScheduledJobMetrics.java`: callback duration/count/lag.
- `common/scheduling/SchedulerErrorHandlerFactory.java`: scheduler error logging/counter.
- `deliveryengine/config/DeliveryEngineAsyncConfig.java`: existing clock and ready-confirmation executor.

Remove `deliveryengine/config/RetrySchedulingConfig.java`. Add focused tests under
`src/test/java/com/example/relay/common/scheduling/` and a final audit under `docs/reviews/`.

## Task 1: Explicit recurring topology and fail-fast routing

**Files:**
- Create: `src/main/java/com/example/relay/common/scheduling/SchedulerNames.java`
- Create: `src/main/java/com/example/relay/common/scheduling/ProductionSchedulerConfig.java`
- Create: `src/main/java/com/example/relay/common/scheduling/UnclassifiedTaskScheduler.java`
- Create: `src/main/java/com/example/relay/deliveryengine/config/DeliveryEngineAsyncConfig.java`
- Delete: `src/main/java/com/example/relay/deliveryengine/config/RetrySchedulingConfig.java`
- Modify: the five scheduled components named in the spec
- Create: `src/test/java/com/example/relay/common/scheduling/ProductionSchedulerTopologyTest.java`
- Create: `src/test/java/com/example/relay/common/scheduling/ScheduledProgressIsolationTest.java`
- Modify: `src/test/java/com/example/relay/support/background/BackgroundExecutionContextPolicyTest.java`

**Interfaces:**
- Produces constants `DELIVERY_PROGRESS`, `DELIVERY_RECONCILIATION`, `PASSWORD_RESET_MAINTENANCE`, `WEBHOOK_DEADLINE`, and `UNCLASSIFIED_DEFAULT`.
- Preserves beans `applicationClock` and `readyWorkConfirmationExecutor` in `DeliveryEngineAsyncConfig`.

- [ ] **Step 1: Write the RED annotation inventory**

Assert this exact route map:

```java
Map.of(
  key("RetryScheduler", "scheduledReleaseDueRetries"),
      route("${relay.retry.scheduler-interval}", SchedulerNames.DELIVERY_PROGRESS),
  key("ReadyWorkDispatcher", "scheduledDispatch"),
      route("${relay.retry.dispatcher-interval}", SchedulerNames.DELIVERY_PROGRESS),
  key("ReconciliationSweeper", "scheduledSweep"),
      route("${relay.reconciliation.interval}", SchedulerNames.DELIVERY_RECONCILIATION),
  key("PasswordResetEmailRecoverySweeper", "sweep"),
      route("${relay.password-reset.email-recovery.interval}", SchedulerNames.PASSWORD_RESET_MAINTENANCE),
  key("PasswordResetTokenCleanupTask", "cleanup"),
      route("${relay.password-reset.cleanup.interval}", SchedulerNames.PASSWORD_RESET_MAINTENANCE));
```

Also assert fixed delay only, no explicit initial delay, and non-blank scheduler. It initially fails for the three unqualified methods.

- [ ] **Step 2: Write RED bean and real Spring routing tests**

Load production scheduling config and assert pool sizes `2/1/2`, prefixes, daemon threads, remove-on-cancel, no delayed/periodic continuation, and five-second await termination. Register a synthetic unqualified callback and assert refresh fails with:

```text
Every production @Scheduled method must declare an approved scheduler
```

Use real Spring 6.2.19 `@EnableScheduling` / `ScheduledAnnotationBeanPostProcessor` registration for three isolated
synthetic application contexts; do not invoke `UnclassifiedTaskScheduler` methods directly:

- unqualified: provide multiple scheduler beans including the `taskScheduler` guard, refresh the context, and assert
  failure during refresh with a causal `IllegalStateException` carrying the stable classification marker;
- approved: explicitly name an approved recording scheduler, refresh successfully, and assert exactly one
  registration on it and zero registrations on another `TaskScheduler` candidate;
- unknown: explicitly name a missing scheduler, assert failure during refresh through Spring's qualifier-resolution
  path with a causal `NoSuchBeanDefinitionException` that identifies the requested qualifier.

Assert failure phase and causal exception shape; do not bind to Spring's complete wrapper stack or full message text.

- [ ] **Step 3: Write RED isolation tests**

Use blocker-entered/release/invoked latches. Prove password-reset blocking cannot stop delivery progress; delivery blocking cannot stop reconciliation or password cleanup; one blocked member still leaves the second domain slot; both occupied slots queue a third task until release. Repeat the two-slot contract for password maintenance. Inspect executor queue and latch state, not thread-name timing.

- [ ] **Step 4: Run RED**

```bash
./mvnw -q test -Dtest=ProductionSchedulerTopologyTest,ScheduledProgressIsolationTest,BackgroundExecutionContextPolicyTest
```

- [ ] **Step 5: Implement names, scheduler factory, and fail-fast default**

Use exact values:

```java
DELIVERY_PROGRESS = "deliveryProgressTaskScheduler";
DELIVERY_RECONCILIATION = "deliveryReconciliationTaskScheduler";
PASSWORD_RESET_MAINTENANCE = "passwordResetMaintenanceTaskScheduler";
WEBHOOK_DEADLINE = "webhookDeadlineTaskScheduler";
UNCLASSIFIED_DEFAULT = "taskScheduler";
```

One private factory sets the spec lifecycle flags. `UnclassifiedTaskScheduler` throws the same classification exception from every `schedule*` method and owns no thread.

- [ ] **Step 6: Move async beans and qualify callbacks**

Move the clock and confirmation executor unchanged. Add the approved scheduler constant to all five annotations. Do not change triggers, callback bodies, or enable checks.
`ReconciliationSweeper.scheduledSweep()` remains one synchronous callback that performs stale-execution recovery and
then dead-letter recovery/publication. Do not split or delegate either phase in P06.

- [ ] **Step 7: Run GREEN**

```bash
./mvnw -q test -Dtest=ProductionSchedulerTopologyTest,ScheduledProgressIsolationTest,ScheduledLoopEnabledSmokeTest,RetrySchedulerPostgresTest,ReadyWorkDispatcherIntegrationTest,ReconciliationSweeperIntegrationTest,PasswordResetEmailRecoverySweeperIntegrationTest
```

Include the existing `PasswordResetTokenCleanupTaskTest` in the selection.

- [ ] **Step 8: Adversarial review checkpoint**

Try removing each qualifier, adding a sixth unqualified callback, blocking each member, throwing from a callback, and closing with both slots occupied. Confirm functional work never routes through `taskScheduler`.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/example/relay/common/scheduling src/main/java/com/example/relay/deliveryengine/config src/main/java/com/example/relay/deliveryengine/{retry,dispatcher,reconciliation} src/main/java/com/example/relay/user/recovery src/test/java/com/example/relay/common/scheduling src/test/java/com/example/relay/support/background/BackgroundExecutionContextPolicyTest.java
git commit -m "feat: isolate production scheduler ownership"
```

## Task 2: Bounded scheduler observability

**Files:**
- Create: `ScheduledJobMetrics.java`, `SchedulerErrorHandlerFactory.java`, and `ScheduledJobMetricsTest.java`
- Modify: `ProductionSchedulerConfig.java`, all five callback classes, and `ProductionSchedulerTopologyTest.java`

**Interfaces:**
- `void ScheduledJobMetrics.run(String job, Duration fixedDelay, Runnable callback)`
- `ErrorHandler SchedulerErrorHandlerFactory.forScheduler(String schedulerName)`
- Metrics: `relay.scheduler.callback.duration{job,outcome}`, `relay.scheduler.invocation.lag{job}`, `relay.scheduler.errors{scheduler}`.

- [ ] **Step 1: Write RED metric tests**

Use `SimpleMeterRegistry` and a package-private `LongSupplier nanoTime` constructor. First invocation records success/count/duration but no lag. A second start at prior completion + delay + 7ms records 7ms. Failure records `outcome=failure`, rethrows unchanged, and advances expected start. Assert only fixed tags.
Also invoke a wrapper whose existing enable check no-ops. Assert it still records a successful callback duration/count
and the applicable admission-lag sample: the metrics measure scheduler admission/callback execution, not business work.
Do not add an `enabled` tag or a business-work counter.

- [ ] **Step 2: Run RED**

```bash
./mvnw -q test -Dtest=ScheduledJobMetricsTest
```

- [ ] **Step 3: Implement metrics/error handling**

Use `System::nanoTime`, one `AtomicLong` expected start per fixed job, clamp lag to zero, and update completion in `finally`. Scheduler error handling increments the fixed scheduler counter, logs, and returns so recurring tasks continue.

- [ ] **Step 4: Wrap annotated entry methods**

Wrap the enable check and work, leaving direct-call work methods unchanged:

```java
scheduledJobMetrics.run("retry-promotion", retryProperties.getSchedulerInterval(), () -> {
    if (retryProperties.isSchedulingEnabled()) {
        releaseDueRetries();
    }
});
```

Apply the five fixed job names from the spec and install fixed-name scheduler error handlers.

- [ ] **Step 5: Verify Boot executor meters**

In a small Boot context assert `executor.active`, `executor.queued`, and `executor.pool.size` exist for recurring scheduler bean names. Do not double-bind executors.

- [ ] **Step 6: Run GREEN and review**

```bash
./mvnw -q test -Dtest=ScheduledJobMetricsTest,ProductionSchedulerTopologyTest,RetrySchedulerPostgresTest,ReconciliationSweeperIntegrationTest
```

Adversarially review disabled callbacks, exception propagation, first call, monotonic time, duplicate registration, and tag cardinality.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/relay/common/scheduling src/main/java/com/example/relay/deliveryengine/{retry,dispatcher,reconciliation} src/main/java/com/example/relay/user/recovery src/test/java/com/example/relay/common/scheduling
git commit -m "feat: observe scheduler lag and callback health"
```

## Task 3: Context-own webhook deadlines

**Files:**
- Modify: `ProductionSchedulerConfig.java`, `DeliveryHttpClientConfig.java`, `ApacheWebhookHttpTransport.java`
- Modify: deadline/response/worker/replay tests and Spring test configurations that construct the transport
- Create/modify: `ProductionSchedulerLifecycleTest.java`

**Interfaces:**
- Produces `webhookDeadlineTaskScheduler`, pool 1.
- Production transport construction requires `TaskScheduler`.
- Each scheduled cancellation returns one `ScheduledFuture<?>`; the transport retains it and cancels it in `finally`.
- A package-private test overload may adapt a local `ScheduledExecutorService` with `ConcurrentTaskScheduler`.

- [ ] **Step 1: Write RED deadline/lifecycle tests**

Assert prefix, pool, daemon and shutdown policies. For success, DNS failure, destination-policy rejection,
connection/TLS failure, response/read failure, and timeout/cancellation races, prove the exact returned future is
retained and canceled in `finally`. Prove fast completion makes the underlying scheduled-executor queue return to its
baseline promptly; do not wait for the 15-second deadline as evidence. Also prove rejection after close and executor
termination on close.

- [ ] **Step 2: Run RED**

```bash
./mvnw -q test -Dtest=ProductionSchedulerLifecycleTest,ApacheWebhookHttpTransportDeadlineTest
```

- [ ] **Step 3: Inject the production scheduler**

Add the bean through the shared factory. Because `DeliveryDeadline` intentionally exposes only monotonic `remaining()`, schedule cancellation at `deadlineScheduler.getClock().instant().plus(deadline.remaining())`; retain the existing monotonic total-deadline authority and checks everywhere else. `scheduleCancellation` returns a non-null
`ScheduledFuture<?>`; `post` stores that exact handle and calls `cancel(false)` from a `finally` covering every
terminal transport path after scheduling. `removeOnCancelPolicy=true` is required for prompt queue removal but does
not replace explicit cancellation. Delete `DeadlineSchedulerHolder` and the two-argument production constructor.

- [ ] **Step 4: Update every construction site**

```bash
rg -n 'new ApacheWebhookHttpTransport' src/main src/test
```

Spring configurations inject the real bean. Low-level tests close their local executor. No static fallback remains.

- [ ] **Step 5: Run GREEN**

```bash
./mvnw -q test -Dtest=ProductionSchedulerLifecycleTest,ApacheWebhookHttpTransportDeadlineTest,ApacheResponseConsumptionIntegrationTest,ApacheWebhookHttpTransportTest,DeliveryWorkerIntegrationTest,DeliveryReplayLifecycleIntegrationTest
```

- [ ] **Step 6: Adversarial review and commit**

Review success, DNS failure, policy rejection, connection/TLS failure, response/read failure, timeout,
completion/cancellation race, scheduling rejection, queued deadlines on close, and multiple contexts. Confirm every
created future has one owner/finally path and the P03 15-second monotonic contract is unchanged.

```bash
git add src/main/java/com/example/relay/common/scheduling/ProductionSchedulerConfig.java src/main/java/com/example/relay/deliveryengine/config/DeliveryHttpClientConfig.java src/main/java/com/example/relay/deliveryengine/http/ApacheWebhookHttpTransport.java src/test/java/com/example/relay/common/scheduling/ProductionSchedulerLifecycleTest.java src/test/java/com/example/relay/deliveryengine src/test/java/com/example/relay/delivery/application/DeliveryReplayLifecycleIntegrationTest.java
git commit -m "fix: bind webhook deadlines to context lifecycle"
```

## Task 4: P00 opt-in topology and shutdown proof

**Files:**
- Modify: `BackgroundExecutionContextPolicyTest.java`
- Modify: `BackgroundExecutionCrossContextRegressionTest.java`
- Modify: `ProductionSchedulerLifecycleTest.java`
- Leave `BackgroundExecutionContextCustomizerFactory.java` unchanged unless a failing RED test proves a central-policy defect.

- [ ] **Step 1: Expand the P00 descriptor**

Use:

```java
record ScheduledDescriptor(
    String component, String method, String fixedDelayProperty, String scheduler) {}
```

Assert the exact five routes. Ordinary context still has no scheduled processor and zero tasks.

- [ ] **Step 2: Add real full-context opt-in registration proof**

Use `@SpringBootTest`, `@EnableTestBackgroundExecution(SCHEDULING)`, one-hour test cadences, and empty DB state. Inspect `ScheduledTaskHolder` runnables and assert all five method/qualifier pairs. Assert production bean topology; do not replace schedulers with fakes.

- [ ] **Step 3: Add cached/dirtied lifecycle proof**

Capture scheduler executor identities. After the existing `AFTER_CLASS` close event, assert shutdown. A second opt-in context owns different executors and no first-context named thread remains.

- [ ] **Step 4: Prove queued callbacks do not start after close**

Queue work behind controlled blockers, begin close, release, and assert the queued latch remains untouched. Assert post-close submission rejects.

Also distinguish the three lifecycle claims in assertions and test names: Spring context close performs early
`shutdown()`, destruction performs `shutdownNow()` plus a bounded five-second await, and executor termination requires
running work to finish or honor interruption. Verify interruptible callbacks terminate and no thread leaks. Do not
treat `daemon=true` as cleanup evidence; it is retained only so an interruption-ignoring callback cannot keep JVM
termination alive after Spring's bounded lifecycle path.

- [ ] **Step 5: Run GREEN**

```bash
./mvnw -q test -Dtest=BackgroundExecutionContextPolicyTest,BackgroundExecutionCrossContextRegressionTest,ProductionSchedulerLifecycleTest,ScheduledLoopEnabledSmokeTest
```

- [ ] **Step 6: Adversarial review and commit**

Review ordinary/scheduling/Rabbit/combined cache identities, refresh failure, close with running/queued work, and `AFTER_CLASS`. Confirm no scheduler-specific disable list exists.

```bash
git add src/test/java/com/example/relay/support/background src/test/java/com/example/relay/common/scheduling/ProductionSchedulerLifecycleTest.java
git commit -m "test: preserve P00 across scheduler domains"
```

## Task 5: Multi-instance state semantics

**Files:**
- Create: `src/test/java/com/example/relay/user/recovery/PasswordResetMaintenanceConcurrencyPostgresTest.java`
- Modify: `ReconciliationSweeperIntegrationTest.java` for the uncovered concurrent dead-letter winner case
- Read/reuse without modification unless a concrete evidence gap is recorded: `RetrySchedulerPostgresTest.java`,
  `ReadyWorkRepositoryPostgresTest.java`, `AttemptExecutionFencingIntegrationTest.java`,
  `AttemptAllocationRepositoryPostgresTest.java`, and the committed replay-concurrency suites

**Interfaces:** consumes existing P04 generation predicates, P05 allocation invariants, ready claim UUIDs, and user-row token serialization. This task adds evidence only where the committed suites do not already prove the invariant.

- [ ] **Step 1: Inventory committed concurrency evidence before writing tests**

Build a small evidence matrix mapping each multi-instance claim to an existing test and assertion. At minimum reuse:

- `RetrySchedulerPostgresTest.concurrentSchedulersPromoteDisjointBatches` for concurrent promotion;
- `ReadyWorkRepositoryPostgresTest.claimSkipsRowsLockedByIndependentTransaction` plus claim-UUID/old-confirm tests
  for ready-work partitioning and fencing;
- `ReconciliationSweeperIntegrationTest` and `AttemptExecutionFencingIntegrationTest` for generation-scoped stale
  recovery, completion/reset races, ABA, and reclaim behavior;
- `AttemptAllocationRepositoryPostgresTest` and the committed replay-concurrency suites for P05 lock order and
  sequence allocation.

Do not recreate those tests merely because the operations are reachable from `@Scheduled` callbacks. Record any
remaining uncovered claim before adding a test.

- [ ] **Step 2: Add only the missing delivery/reconciliation evidence**

The amendment inventory found no committed proof for two callers racing the dead-letter touch/publication winner, so
add that one focused PostgreSQL/Rabbit test to `ReconciliationSweeperIntegrationTest`. Add no new promotion,
ready-dispatch, P04, or P05 concurrency scenario unless the evidence matrix names another specific missing invariant.
Preserve all existing ownership predicates; scheduler invocation is not a new authority.

- [ ] **Step 3: Add focused password-reset recovery and cleanup evidence**

Existing password-reset tests do not cover two concurrent recovery sweepers or concurrent cleanup. Gate two recovery
callers on one stale token, capture publications, and assert the user-row lock leaves exactly one unused token with
persisted `firstRequestedAt` preserved at PostgreSQL precision. Redundant rows/publications remain class B and must be
reported, not hidden behind scheduler leadership. Run cleanup concurrently and prove no eligible row, constraint
violation, token resurrection, or authorization-state change.

**Mandatory stop condition:** If concurrent password-reset recovery can produce more than harmless duplicate work—for
example multiple simultaneously usable tokens, incorrect invalidation, authorization-state corruption, or another
security-relevant semantic change—stop and report the defect. Do not introduce scheduler leadership or
opportunistically redesign password-reset semantics inside P06.

- [ ] **Step 4: Run GREEN**

```bash
./mvnw -q test -Dtest=PasswordResetMaintenanceConcurrencyPostgresTest,RetrySchedulerPostgresTest,AttemptExecutionFencingIntegrationTest,AttemptExecutionMigrationLifecyclePostgresTest,AttemptExecutionRepositoryPostgresTest,AttemptAllocationRepositoryPostgresTest,AttemptReplayConcurrencyPostgresTest,DeliveryReplayConcurrencyPostgresTest,ReadyWorkRepositoryPostgresTest,ReconciliationSweeperIntegrationTest
```

- [ ] **Step 5: Adversarial review and commit**

Review the evidence matrix first, then only new coverage: separate transactions/contexts, dead-letter winner if it was
a gap, PostgreSQL timestamp precision, ordinary reset racing recovery, cleanup racing recovery, and the password-reset
stop condition. Failures must be state-invariant failures, not singleton expectations.

```bash
git add src/test/java/com/example/relay/user/recovery/PasswordResetMaintenanceConcurrencyPostgresTest.java src/test/java/com/example/relay/deliveryengine/reconciliation/ReconciliationSweeperIntegrationTest.java
git commit -m "test: prove multi-instance scheduler state safety"
```

## Task 6: Full regression and fresh final audit

**Files:**
- Create: `docs/reviews/2026-10-05-p06-production-scheduler-final-audit.md`
- Modify the P06 spec only to append implementation revision/evidence or an approved deviation.

- [ ] **Step 1: Repeat the complete source inventory**

```bash
rg -n --hidden --glob '!graphify-out/**' --glob '!.git/**' '@Scheduled|TaskScheduler|SchedulingConfigurer|ScheduledTaskRegistrar|ScheduledExecutorService|scheduleAtFixedRate|scheduleWithFixedDelay|\.schedule\(|@EnableScheduling|TaskExecutor|ExecutorService|Executors\.|ApplicationRunner|CommandLineRunner|SmartLifecycle|@PostConstruct' src/main/java src/main/resources pom.xml
```

Account for every result. No static/global scheduler, unqualified callback, functional default, undocumented recurring loop, or third-party scheduler may remain.

- [ ] **Step 2: Run focused P06 tests three times**

```bash
for run in 1 2 3; do
  ./mvnw -q test -Dtest=ProductionSchedulerTopologyTest,ScheduledProgressIsolationTest,ScheduledJobMetricsTest,ProductionSchedulerLifecycleTest,BackgroundExecutionContextPolicyTest,BackgroundExecutionCrossContextRegressionTest,PasswordResetMaintenanceConcurrencyPostgresTest,ScheduledLoopEnabledSmokeTest || exit 1
done
```

Record counts/results. Treat flakiness as a defect; do not add arbitrary sleeps.

- [ ] **Step 3: Run P00–P05 regressions**

Resolve the exact committed lists from the prior plans and run their background, bytes, bounded-response, destination, fencing, reconciliation, allocation, replay, migration, and repository suites. Minimum:

```bash
./mvnw -q test -Dtest=BackgroundExecutionContextPolicyTest,BackgroundExecutionCrossContextRegressionTest,DeliveryWorkerIntegrationTest,ApacheResponseConsumptionIntegrationTest,WebhookDestinationAdversarialIntegrationTest,ReconciliationSweeperIntegrationTest,ReadyWorkRepositoryPostgresTest,AttemptAllocationRepositoryPostgresTest,AttemptReplayConcurrencyPostgresTest,DeliveryReplayConcurrencyPostgresTest
```

- [ ] **Step 4: Run uncapped gate**

```bash
./mvnw test
```

Run the formatting/static command from CI. Record unrelated pre-existing failures precisely; P06-touched files must pass.

- [ ] **Step 5: Inspect a real runtime graph**

Record all scheduler/executor beans, registered methods/qualifiers, pools/prefixes/daemon/shutdown policies, Micrometer meters, zero ordinary P00 callbacks, five opt-in callbacks, and terminated opt-in executors after close.

- [ ] **Step 6: Write the final audit**

Include resulting inventory, graph reconciliation, revision, three-run evidence, P00–P05/full-suite results, lifecycle evidence, multi-instance findings, metrics, and deviations. State explicitly that scheduler isolation does not eliminate database/Rabbit/network contention.

- [ ] **Step 7: Final adversarial review**

Challenge all fifteen spec invariants. Temporarily add an unqualified callback, prove both guard layers fail, remove it, and rerun inventory. Run `git diff --check`; verify no retry/business/schema change.

- [ ] **Step 8: Commit**

```bash
git add docs/reviews/2026-10-05-p06-production-scheduler-final-audit.md docs/superpowers/specs/2026-10-05-p06-production-scheduler-ownership-and-progress-isolation-design.md
git commit -m "docs: record P06 scheduler verification"
```

## Implementation stop gate

After approval but before Task 1, recheck `main`. Stop if the shared defect disappeared, framework routing changed, P00 no longer centrally suppresses registration, a job now requires deployment singleton execution, P04/P05 conflicts appear, reconciliation would have to be split to meet a required invariant, concurrent password-reset recovery exposes more than redundant work, or the fix requires business-semantic changes.
