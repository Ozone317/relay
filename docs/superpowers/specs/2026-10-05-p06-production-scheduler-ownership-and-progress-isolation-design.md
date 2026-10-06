# P06 — Production Scheduler Ownership and Progress Isolation Design

**Date:** 2026-10-05  
**Status:** AMENDED 2026-10-06 — AWAITING REVIEW; implementation must not resume without explicit approval
**Scope:** production scheduled-callback routing, capacity isolation, scheduler lifecycle, P00 compatibility, and bounded scheduler health telemetry  
**Out of scope:** retry policy or cadence changes, delivery/replay semantics, distributed scheduler leadership, P01–P05 redesign, billing/tiering, and general observability work

## 1. Decision summary

The historical P06 defect still exists on current `main` (`6a2c1e3`). Relay has one Spring `TaskScheduler` bean,
`retryTaskScheduler`, with pool size one. `RetryScheduler` and `ReadyWorkDispatcher` name it explicitly; the three
unqualified scheduled methods also resolve to it. A synchronous callback from any of the five jobs can therefore
prevent every other callback from being invoked.

P06 will replace implicit ownership with these explicit domains:

| Bean | Prefix | Pool | Scheduled owners |
|---|---|---:|---|
| `deliveryProgressTaskScheduler` | `relay-delivery-progress-` | 2 | retry promotion; ready-work dispatch |
| `deliveryReconciliationTaskScheduler` | `relay-delivery-reconciliation-` | 1 | execution/dead-letter reconciliation |
| `passwordResetMaintenanceTaskScheduler` | `relay-password-reset-maintenance-` | 2 | reset-email recovery; reset-token cleanup |
| `webhookDeadlineTaskScheduler` | `relay-webhook-deadline-` | 1 | demand-driven HTTP cancellation deadlines; not an `@Scheduled` owner |
| `taskScheduler` | no threads | 0 | fail-fast guard for any unclassified/unqualified `@Scheduled` method |

Each pair on a two-thread scheduler contains exactly two fixed-delay tasks. Spring/JDK periodic-task semantics prevent
one fixed-delay task from overlapping itself, so one blocked member can consume at most one thread and cannot prevent
the other member from starting. Reconciliation remains separate because it is recovery work with a slower cadence and
potentially many database and Rabbit operations. The static webhook deadline executor becomes context-owned but is not
mixed with recurring maintenance.

All five recurring callbacks also pass through a context-owned Relay admission boundary immediately before scheduler
metrics and business work. Once that boundary closes on `ContextClosedEvent`, an executor wrapper may still be dequeued
by the JDK, but it cannot begin Relay business work unless it linearized admission before the close transition.

No scheduled job requires deployment-wide leader election for state correctness. PostgreSQL claims, row locks, P04
execution-generation fencing, and P05 allocation locks remain authoritative.

## 2. Investigation basis

The audit used current source, resolved dependencies, and the P00/P04/P05 artifacts. Historical readiness documents
were treated as evidence to recheck, not as a substitute for source inspection.

- Spring Boot: `3.5.16`
- Spring Framework: `6.2.19`
- Java: `21`
- Micrometer: `1.15.12`
- Production scheduling activation: `RelayApplication` directly declares `@EnableScheduling`.
- Flyway schema: V1 through V14, including P04 generation fencing and P05 sequence constraints/indexes.

The main-source audit searched for `@Scheduled`, `TaskScheduler`, `SchedulingConfigurer`, `ScheduledTaskRegistrar`,
`ScheduledExecutorService`, programmatic `schedule*` calls, `TaskExecutor`/executor beans, `@Async`, startup runners,
post-construct recurring work, static executors, and third-party scheduler dependencies. There is no Quartz, ShedLock,
JobRunr, Spring Batch scheduler, `SchedulingConfigurer`, custom registrar, `@Async`, or startup-created recurring loop.

## 3. Fresh current-main inventory

All five annotation-driven jobs use fixed delay with no declared initial delay. In Spring 6.2 this means an initial
delay of zero, followed by the configured delay measured from completion. One periodic task never overlaps itself,
but distinct tasks compete for scheduler threads. None of the callbacks has an application-level runtime bound.

| Component and method | Purpose | Cadence / enablement | Current route | Synchronous work and dependencies | Failure / overlap / shutdown |
|---|---|---|---|---|---|
| `RetryScheduler.scheduledReleaseDueRetries` | Promote due `SCHEDULED` Attempts to durable `CREATED` ready work | fixed delay `relay.retry.scheduler-interval=1s`; gated inside callback by `relay.retry.scheduling-enabled` (Java default `true`) | explicit `retryTaskScheduler`, pool 1 | One PostgreSQL CTE/update, `FOR UPDATE SKIP LOCKED`, batch 100. No Rabbit or async delegation. Expected short; worst case unbounded by an application query timeout. | Exception escapes to Spring's recurring-task error handler, which logs/suppresses and preserves later ticks. No same-task overlap. Every instance runs; row locks make it safe. |
| `ReadyWorkDispatcher.scheduledDispatch` | Claim unpublished `CREATED` work and publish Rabbit delivery tasks | fixed delay `relay.retry.dispatcher-interval=1s`; same enable flag | explicit `retryTaskScheduler`, pool 1 | PostgreSQL claim, then up to 100 synchronous `RabbitTemplate.convertAndSend` calls. Confirm outcomes delegate to `readyWorkConfirmationExecutor` (core 2, max 4, queue 1,000). Callback does not wait for confirms. | Claim and per-publication startup failures are caught/logged; async completion failures are logged. A blocked DB/Rabbit call can hold the scheduler thread. No same-task overlap. Every instance runs; UUID claims fence confirmations. |
| `ReconciliationSweeper.scheduledSweep` | Revoke stale P04 executions and republish unnotified DEAD work | fixed delay `relay.reconciliation.interval=30s`; gated by `relay.reconciliation.scheduling-enabled` (Java default `true`) | unqualified, therefore actual `retryTaskScheduler`, pool 1 | One synchronous callback calls stale-execution recovery and then dead-letter recovery/publication on the scheduler thread. PostgreSQL candidate scans; up to 100 generation-scoped reset transactions; up to 100 dead-letter touch transactions and Rabbit publishes. No delegation. | Any uncaught failure, including one in the first phase, aborts the remaining sweep and is logged/suppressed by Spring; next tick remains scheduled. No same-task overlap. Every instance runs; generation/age and touch predicates make races safe. |
| `PasswordResetEmailRecoverySweeper.sweep` | Reissue a fresh reset token/email for stale unconfirmed dispatches, or retire an exhausted recovery chain | fixed delay `relay.password-reset.email-recovery.interval=60s`; no enable property | unqualified, therefore actual `retryTaskScheduler`, pool 1 | PostgreSQL query up to 100; per candidate, user-row locking, token invalidation/insertion, and Rabbit email publish. No async delegation from the callback. | One exception aborts later candidates; Spring logs/suppresses it and preserves later ticks. No same-task overlap. Multiple instances may select the same snapshot; user-row locking preserves state but can cause redundant reissues/emails. |
| `PasswordResetTokenCleanupTask.cleanup` | Delete expired reset-token rows past retention | fixed delay `relay.password-reset.cleanup.interval=1d`; no enable property | unqualified, therefore actual `retryTaskScheduler`, pool 1 | One transactional bulk PostgreSQL delete using retention `7d`; not batched. Expected infrequent; worst case depends on table volume/locks and has no application query timeout. | Failure rolls back and is logged/suppressed by Spring; next tick remains scheduled. No same-task overlap. Multiple instances may run; concurrent deletes are state-safe but wasteful. |

### 3.1 Other production scheduling/executor facilities

| Facility | Autonomous? | Capacity / ownership | Relevance |
|---|---|---|---|
| `ApacheWebhookHttpTransport.DeadlineSchedulerHolder.INSTANCE` | No; schedules one cancellation per active HTTP delivery | static single-thread daemon `ScheduledExecutorService`, prefix exactly `relay-webhook-deadline`; never shut down with a context | Demand-driven scheduler with a lifecycle leak across closed contexts/classloaders. The transport already retains the returned `ScheduledFuture` and cancels it in `finally`, but the executor is not configured for remove-on-cancel, so a fast completion can remain queued until its deadline. P06 preserves explicit future ownership and makes removal and lifecycle context-owned without changing the 15-second monotonic deadline. |
| `readyWorkConfirmationExecutor` | No | `ThreadPoolTaskExecutor`, core 2/max 4/queue 1,000, prefix `relay-ready-confirm-`, wait/await 5s | Receives publisher-confirm continuation work only after dispatcher callback invocation. It does not protect scheduler invocation capacity and must not be multiplied with scheduler pool sizing. |
| `virtualThreadExecutor` | No | virtual thread per delivery task; Spring closes the `ExecutorService` bean | Used by Rabbit listener work, not scheduled callbacks. |
| `deliveryDnsExecutor` | No | fixed 40 daemon threads, bounded queue 40, abort rejection, Spring `shutdownNow` | Demand-driven DNS work, bounded independently from schedulers. |
| Apache HttpClient eviction thread(s) | Library lifecycle, not a Relay scheduled callback | owned by the closeable HTTP client bean | Closed with the client; no `@Scheduled` routing impact. |

`ScheduledStatusConstraintGuard` is an `ApplicationRunner`, but it performs a one-time schema guard and creates no
recurring work.

## 4. Proven current routing

Spring Framework 6.2.19 implements the documented `@Scheduled.scheduler` qualifier through
`ScheduledMethodRunnable.getQualifier()` and `TaskSchedulerRouter`:

1. a non-empty qualifier resolves a `TaskScheduler` or `ScheduledExecutorService` by qualifier/bean name;
2. an empty qualifier first resolves a unique `TaskScheduler` by type;
3. only if there are multiple candidates does it try the documented default name `taskScheduler`;
4. if neither type nor name resolves, it creates a local single-thread fallback executor.

Boot 3.5.16 creates its default `taskScheduler` only under
`@ConditionalOnMissingBean({TaskScheduler.class, ScheduledExecutorService.class})`. Relay's
`retryTaskScheduler` therefore makes Boot back off. Because it is the only `TaskScheduler`, all empty qualifiers route
to it. Bean declaration order and the word "retry" in its name have no routing effect.

Current graph:

```text
@EnableScheduling
  -> ScheduledAnnotationBeanPostProcessor
     -> TaskSchedulerRouter
        -> scheduler="retryTaskScheduler" -------------------+
        |   RetryScheduler                                   |
        |   ReadyWorkDispatcher                              |
        |                                                     v
        -> empty qualifier -> unique TaskScheduler -> retryTaskScheduler (pool 1)
            ReconciliationSweeper
            PasswordResetEmailRecoverySweeper
            PasswordResetTokenCleanupTask
```

## 5. Reproduced failure/liveness evidence

A review-only JShell probe compiled current `main`, registered the actual `RetrySchedulingConfig`, and used Spring
6.2.19's actual `TaskSchedulerRouter`. It used latches and the scheduler's queue state; it did not use sleep as a
correctness condition and did not touch PostgreSQL or RabbitMQ.

1. An unqualified callback was scheduled through the router and entered on `relay-retry-1`.
2. The callback blocked on a release latch.
3. A retry-progress callback was scheduled immediately on the named `retryTaskScheduler`.
4. While the blocker held the only thread, the scheduler queue contained the retry callback and its invocation latch
   remained at one.
5. Releasing the unrelated callback caused the retry invocation latch to reach zero.

Observed evidence:

```text
P06_SCHEDULERS=[retryTaskScheduler]
P06_BLOCKER_THREAD=relay-retry-1
P06_RETRY_QUEUED_WHILE_BLOCKED=1
P06_RETRY_CALLBACK_COUNT_WHILE_BLOCKED=1
P06_RETRY_CALLBACK_COUNT_AFTER_RELEASE=0
```

This proves scheduler callback invocation delay. It does not involve or claim to fix async confirmation delay,
database lock/contention, Rabbit publication/consumption delay, webhook execution, or shutdown.

The focused P00 suite also passed on current main:

```text
./mvnw -q test \
  -Dtest=BackgroundExecutionContextPolicyTest,BackgroundExecutionCrossContextRegressionTest,ScheduledLoopEnabledSmokeTest
```

### 5.1 Spring 6.2.19 close-window evidence

Task 4's strengthened RED test saturated every worker in all four scheduler pools then present in the topology,
including the demand-driven webhook-deadline scheduler, queued an immediately-ready one-shot task behind each pool,
initiated context close, observed the early executor `shutdown()`, and then released the blockers. All four queued
one-shot tasks executed before bean destruction (`queued latch: 4 -> 0`). A separate JDK 21 probe reproduced the result
with one occupied `ScheduledThreadPoolExecutor` worker. This evidence establishes the executor behavior; the Relay
business-admission gate designed below intentionally covers only the three recurring-business schedulers.

This is expected behavior. Spring Framework 6.2.19's `ExecutorConfigurationSupport.initiateShutdown()` calls ordinary
`ExecutorService.shutdown()` on `ContextClosedEvent`; destruction later calls `shutdownNow()` and awaits termination.
For a non-periodic scheduled task, `executeExistingDelayedTasksAfterShutdownPolicy=false` removes the task only while
its delay remains positive. An already-ready one-shot has no remaining delay and may execute when capacity becomes
available. The setting still cancels not-yet-due one-shots, and
`continueExistingPeriodicTasksAfterShutdownPolicy=false` cancels recurring fixed-delay tasks.

Therefore P06 does not claim that the executor will never dequeue or invoke an already-ready wrapper after early
shutdown. The production invariant is application-level: once the Relay admission boundary has closed, no scheduled
callback that has not already crossed that boundary may begin business work.

## 6. Proposed ownership graph and rationale

```text
ScheduledAnnotationBeanPostProcessor -> TaskSchedulerRouter
  retry promotion -------- scheduler="deliveryProgressTaskScheduler" --------+
  ready dispatch ---------- scheduler="deliveryProgressTaskScheduler" --------+ pool 2
  reconciliation ---------- scheduler="deliveryReconciliationTaskScheduler" ---- pool 1
  reset email recovery ---- scheduler="passwordResetMaintenanceTaskScheduler" --+
  reset token cleanup ----- scheduler="passwordResetMaintenanceTaskScheduler" --+ pool 2
  any empty qualifier ----- default bean "taskScheduler" -> fail startup

five recurring method wrappers -> ScheduledCallbackRunner -> ScheduledCallbackAdmission
  admitted -> ScheduledJobMetrics -> existing enable check and business work
  denied   -> bounded admission-denied metric; no business work

Webhook transport -> webhookDeadlineTaskScheduler (pool 1, one-shot local cancellations)
Ready confirmations -> readyWorkConfirmationExecutor (unchanged worker capacity)
```

### 6.1 Delivery progress: pool 2

Retry promotion and ready dispatch are successive stages of the same durable delivery-progress loop. Sharing the
domain is intentional, but sharing one thread is not. There are exactly two fixed-delay tasks; each task is
non-overlapping, so pool size two guarantees one available execution slot per loop without permitting additional
same-loop concurrency. The dispatcher still delegates only confirmation handling to its existing bounded executor.

### 6.2 Delivery reconciliation: pool 1

Reconciliation is isolated from normal promotion/dispatch because it can execute hundreds of sequential DB/Rabbit
operations and because recovery must remain invocable when either normal progress callback blocks. It has one
fixed-delay method and its database predicates already support multi-instance concurrency, so pool size one is enough.
`ReconciliationSweeper.scheduledSweep()` remains one synchronous callback: stale-execution recovery runs first, then
dead-letter recovery/publication. P06 provides isolation between this whole callback and other scheduler domains; it
does not provide progress isolation between those two internal phases. No observed correctness or required-progress
invariant justifies splitting the sweeper in P06. If implementation evidence contradicts that conclusion, stop for an
owner decision rather than expanding P06.

### 6.3 Password-reset maintenance: pool 2

Recovery and cleanup belong to the password-reset subsystem but have different progress needs. Two fixed-delay tasks
and two threads give each a slot while preserving non-overlap per task. A long bulk delete cannot delay recovery, and a
slow recovery batch cannot delay retention cleanup. No extra worker executor is introduced.

### 6.4 Webhook deadlines: pool 1

Cancellation callbacks are constant-size local `request.cancel()` calls and are bounded by active delivery
concurrency. One thread preserves current concurrency and avoids multiplying HTTP execution. It is separate from all
maintenance domains because a delayed cancellation directly weakens the delivery deadline. Scheduling a cancellation
must return a `ScheduledFuture<?>`; the transport retains that exact future for the lifetime of the HTTP operation and
cancels it with `cancel(false)` in `finally` on every terminal path after scheduling: success, DNS failure,
destination-policy rejection, connection/TLS failure, response/read failure, and timeout/cancellation races. The
existing `DeliveryDeadline` monotonic `remaining()` calculation remains the total-deadline authority.

### 6.5 Boundedness

`ScheduledThreadPoolExecutor` uses a delayed queue, which is not size-bounded. For recurring domains the number of
long-lived periodic entries is statically bounded to 2, 1, and 2; callbacks do not enqueue arbitrary work onto those
schedulers. Deadline entries are bounded in normal operation by active deliveries. Explicit `ScheduledFuture`
cancellation removes ownership when the HTTP operation terminates; `removeOnCancelPolicy=true` then removes that
canceled entry promptly instead of retaining it until its deadline. Either measure without the other is insufficient.

## 7. Scheduler construction and lifecycle

All four real schedulers use `ThreadPoolTaskScheduler` and explicitly set:

- the approved pool size and thread-name prefix;
- daemon threads, as a last-resort JVM-liveness property rather than a substitute for Spring lifecycle ownership;
- `acceptTasksAfterContextClose=false`;
- `waitForTasksToCompleteOnShutdown=false`;
- `continueExistingPeriodicTasksAfterShutdownPolicy=false`;
- `executeExistingDelayedTasksAfterShutdownPolicy=false`;
- `removeOnCancelPolicy=true`;
- await termination of 5 seconds after shutdown initiation;
- a logging-and-counting error handler that suppresses recurring callback exceptions after recording them, preserving
  Spring's present repeat-after-failure behavior.

This choice was rechecked against Spring Framework 6.2.19. With `acceptTasksAfterContextClose=false` and
`waitForTasksToCompleteOnShutdown=false`, `ExecutorConfigurationSupport` performs early `executor.shutdown()` on the
context-close event. Bean destruction then calls `shutdownNow()`, cancels remaining queued tasks, interrupts running
tasks, and calls `awaitTermination(5 seconds)`. `ThreadPoolTaskScheduler` creates a
`ScheduledThreadPoolExecutor`; the explicit delayed/periodic continuation policies and remove-on-cancel policy apply
to that executor. `CustomizableThreadCreator` defaults to non-daemon, so daemon status is a deliberate Relay setting.

These are three different guarantees. Context close is bounded because Spring waits at most five seconds. Executor
termination is guaranteed only when running callbacks finish or honor interruption; `awaitTermination` cannot kill an
interruption-ignoring callback. JVM termination is the reason to retain `daemon=true`: after the bounded Spring path,
such a callback cannot keep the process alive. This is appropriate because DB/Rabbit maintenance is durable,
re-entrant, and protected by existing claims/fencing rather than by in-memory completion, while a webhook-deadline
callback only cancels an HTTP request local to the terminating JVM. Tests still must prove normal interruptible
callbacks terminate and threads do not leak; daemon status is not accepted as evidence of correct cleanup.

Spring/JDK executor policy alone is insufficient for an already-ready one-shot queued behind occupied workers: after
early `shutdown()`, that wrapper can still run when a worker becomes free. P06 therefore adds two narrowly scoped
components:

- `ScheduledCallbackAdmission`, a context-owned singleton and highest-precedence `ContextClosedEvent` listener;
- `ScheduledCallbackRunner`, a thin coordinator used by every production `@Scheduled` entry method.

`ScheduledCallbackAdmission` owns a private monitor, an `open` flag, and an admitted-callback count used for bounded
state inspection. `runIfOpen(Runnable)` acquires the monitor, rejects when closed, or records admission while still
holding the monitor; it then releases the monitor and invokes the supplied body in `try/finally`. The close listener
acquires the same monitor and changes `open` to false. It does not wait for admitted callbacks and does not interrupt
them. Consequently admission and close have a total order:

1. If admission owns the monitor first, it records admission before releasing the monitor. That callback is considered
   running work even if the Java thread is descheduled before the supplied body begins; close may then proceed without
   waiting, and the admitted callback follows the existing destruction/interruption contract.
2. If close owns the monitor first, it changes the state to closed before releasing the monitor. Every later admission
   attempt is denied and its supplied business body is never invoked.

There is no independent `if (closing)` followed by a later call. The state decision and admission record are one
synchronized linearization operation, so a callback cannot observe open, allow close to linearize, and only afterward
commit admission. The listener retains its owning `ApplicationContext`, ignores propagated close events whose
`event.getApplicationContext()` is not that exact instance, and uses highest precedence so Relay closes admission at
the start of its handling of its own context-close event, before the scheduler's normal early-shutdown listener. The
exact application-level boundary is the synchronized open-to-closed transition, not event-object creation or JDK
wrapper dequeue. Closing a child context cannot close a parent-owned gate.

`ScheduledCallbackRunner` composes this lifecycle decision with `ScheduledJobMetrics`: admitted work enters the
existing metrics wrapper and then the existing enable check/business body; denied work never enters that wrapper.
Keeping the primitive separate prevents metrics from owning application lifecycle. A `ThreadPoolTaskScheduler`
`TaskDecorator` is rejected: in Spring 6.2.19 it wraps the underlying `RunnableScheduledFuture`, and skipping that
wrapper can prevent one-shot future completion or periodic rescheduling bookkeeping.

The complete recurring entry map is:

| Annotated entry | Admitted body |
|---|---|
| `RetryScheduler.scheduledReleaseDueRetries()` | existing enable check, then `releaseDueRetries()` |
| `ReadyWorkDispatcher.scheduledDispatch()` | existing enable check, then `dispatchOnce()` |
| `ReconciliationSweeper.scheduledSweep()` | existing enable check, then the single synchronous two-phase `sweep()` |
| `PasswordResetEmailRecoverySweeper.scheduledSweep()` | existing `sweepOnce()` body |
| `PasswordResetTokenCleanupTask.scheduledCleanup()` | after admission, the existing delete body inside an explicit `TransactionTemplate` transaction |

Each annotated entry invokes `ScheduledCallbackRunner` as its first scheduling concern; direct-call work methods stay
available for existing non-scheduled tests and callers and do not acquire admission independently.

`PasswordResetTokenCleanupTask.scheduledCleanup()` currently has method-level `@Transactional` advice, which would
open a transaction before method entry and therefore before the admission boundary. P06 moves only the scheduled
path's unchanged delete operation into an explicit `TransactionTemplate` inside the admitted body and removes
`@Transactional` from the annotated entry method. The direct-call `cleanup()` method retains its existing transaction.
A denied scheduled wrapper therefore acquires no transaction/connection and performs no repository call; an admitted
wrapper still performs the same delete atomically. This is transaction-boundary placement, not a business-semantic
change.

This gate applies only to the five annotation-driven recurring business callbacks. It is not a generic shutdown
framework, does not govern worker executors, and does not change P04/P05 or database ownership semantics. The
demand-driven webhook deadline scheduler does not participate: its one-shot callback cancels an HTTP request already
owned by the closing JVM, and its lifecycle is already governed by explicit `ScheduledFuture` cancellation plus the
context-owned scheduler. Denying that cancellation through the recurring-business gate could prolong transport work
during shutdown and provides no business-admission protection.

Pool sizes and lifecycle flags are topology invariants in code, not operator tuning knobs. Existing business cadence,
batch, retention, grace, and enable properties remain the configuration surface. This avoids an override silently
reducing a pool below its isolation guarantee. No new per-job disable properties are introduced.

## 8. Explicit Spring routing and fallback prevention

Spring 6.2.19's documented `@Scheduled(scheduler = "beanName")` mechanism is sufficient. Every production scheduled
method will name one of the three recurring domain schedulers. Relay will not add a `SchedulingConfigurer`, replace the
registrar, mark a functional scheduler `@Primary`, or depend on bean creation order.

A bean named `taskScheduler` will implement `TaskScheduler` only to throw an explanatory exception from every schedule
method. It owns no executor or thread. This uses Spring's documented default-name resolution to make an accidental
empty qualifier fail application startup instead of silently creating/routing to a fallback scheduler.

A test inventory is the primary architecture guard. It scans every production `@Scheduled` method and asserts the
complete `(class, method, trigger, property, scheduler)` mapping. It also asserts that no production callback has an
empty scheduler qualifier and that every referenced bean is an approved real scheduler. Three framework-level tests
use real `@EnableScheduling` / `ScheduledAnnotationBeanPostProcessor` registration and Spring 6.2.19 routing:

1. An unqualified synthetic callback resolves the documented default name `taskScheduler`, reaches
   `UnclassifiedTaskScheduler`, and fails during application-context refresh. Assert a causal
   `IllegalStateException` carrying Relay's stable classification marker, not the complete wrapper/message text.
2. A callback naming an approved scheduler refreshes successfully and registers exactly once on that scheduler and
   zero times on another `TaskScheduler`, despite multiple candidates.
3. A callback naming an unknown scheduler fails during context refresh through Spring's qualifier lookup. Assert a
   causal `NoSuchBeanDefinitionException` and the requested qualifier, not the complete error text.

Calling `UnclassifiedTaskScheduler` directly is not sufficient evidence for any of these routing contracts.

## 9. Multi-instance semantics

| Job/facility | Class | Semantics and authority |
|---|---|---|
| Retry promotion | A | Run on every instance. `FOR UPDATE SKIP LOCKED` partitions due rows. PostgreSQL status and row locks are authoritative. |
| Ready dispatch | A | Run on every instance. Atomic claim UUID/lease and fenced confirmation make concurrent claims safe. Rabbit duplicates remain possible after ambiguous confirms; P04 worker claim fencing prevents duplicate execution ownership. |
| Reconciliation | A | Run on every instance. Candidate reads may overlap, but P04 generation/age-scoped reset and dead-letter touch affected-row checks select the winner. |
| Reset email recovery | B | Duplicate invocation is state-safe but can be wasteful and can send redundant reset emails. The user-row lock serializes token replacement and leaves one live token; deployment singleton scheduling would not eliminate races with ordinary reset requests. This is not a reason for scheduler leadership. |
| Reset-token cleanup | B | Concurrent deletes are safe and the second instance normally deletes zero rows, but duplicate scans/work are wasteful. |
| Webhook deadline | A | Per-instance local ownership is required because each deadline cancels an HTTP request in that JVM. |

No job is class C. P06 introduces no distributed lock or leader election. If future evidence changes reset-recovery
product expectations (for example, forbidding multiple recovery emails), that needs a database claim/idempotency
design, not scheduler singleton behavior.

A bounded two-caller PostgreSQL probe during the amendment pass found two recovery rows but exactly one unused token;
it exposed no simultaneous-token, invalidation, authorization-state, or other security-relevant corruption. The known
class-B outcome remains redundant recovery work/publication. The committed implementation test must compare persisted
`firstRequestedAt` values at PostgreSQL precision. If concurrent recovery instead exposes more than that redundant
work, implementation stops: P06 must not add scheduler leadership or redesign password-reset semantics.

P04 remains the only authority for Attempt execution claim/completion/revocation. P05 remains the only authority for
Endpoint-before-Delivery lock order, sequence allocation, generation-zero insertion, and V13/V14 constraints/indexes.

## 10. P00 interaction

P00 removes the scheduled annotation processor from ordinary Spring TestContexts. P06 does not change that mechanism.
Consequently:

- ordinary contexts still register zero autonomous production callbacks, including future callbacks;
- scheduler beans may exist but have no recurring tasks and lazily create no threads until work is submitted;
- the admission gate/runner beans may exist, but no ordinary context can autonomously reach them because the scheduled
  annotation processor is absent; context close still closes the gate without creating scheduler work or threads;
- `@EnableTestBackgroundExecution(SCHEDULING)` restores the real annotation processor and therefore the real production
  qualifiers/topology;
- the opt-in annotation retains `@DirtiesContext(AFTER_CLASS)`, so live schedulers are not cached beyond the class;
- context-cache identity remains based only on P00 capability bits;
- no scheduler-specific property suppression list is added;
- the fail-fast default scheduler does not weaken P00: ordinary contexts never ask it to register a callback.

The policy test will be extended from a method-name inventory to the exact routing inventory and will prove that an
opt-in full context registers all five production callbacks with their production scheduler qualifiers.

## 11. Observability

P06 adds only bounded scheduler-health telemetry:

- `relay.scheduler.callback.duration{job,outcome}` timer; its count is the execution counter;
- `relay.scheduler.invocation.lag{job}` timer, measured at callback entry against the prior fixed-delay completion plus
  configured interval; the first invocation has no lag sample;
- `relay.scheduler.admission.denied{job}` counter for a dequeued wrapper denied before Relay job admission;
- `relay.scheduler.errors{scheduler}` counter from the scheduler error handler;
- Spring Boot's existing executor binder for each `ThreadPoolTaskScheduler`, exposing active, queued, pool-size,
  completed, and related executor gauges tagged/named by the stable bean name.

Job values are the five fixed names `retry-promotion`, `ready-dispatch`, `delivery-reconciliation`,
`password-reset-email-recovery`, and `password-reset-token-cleanup`, represented by a closed `ScheduledJob` enum used
by `ScheduledCallbackRunner` and `ScheduledJobMetrics`. Scheduler values are the four fixed bean names.
No user, Message, Delivery, Attempt, Endpoint, token, or other entity identifier is a metric tag.

These metrics describe scheduler admission and callback execution, not whether business work was performed. If a
scheduled callback is admitted but its existing business enable flag makes the wrapped body a no-op, it still records
one successful callback duration and, after the first invocation, an admission-lag sample. P06 adds no `enabled` tag
and no business-work counter.

An executor wrapper dequeued after close but denied at the Relay admission boundary is not a job invocation and is not
a successful no-op. It records neither `relay.scheduler.callback.duration` nor `relay.scheduler.invocation.lag`, and
it does not advance that job's next expected-start state. It increments
`relay.scheduler.admission.denied{job}` once. `job` is restricted to the same five fixed values, so the new counter is
bounded. This distinguishes a scheduler/JDK wrapper invocation from admission to Relay callback execution.

The lag measure is scheduler admission lag, not database/Rabbit latency; callback duration captures the latter only as
time spent inside the callback and does not diagnose its downstream cause. Alert thresholds are an operational owner
decision after observing production baselines.

## 12. Deterministic test strategy

1. Block password-reset maintenance with a latch; prove retry-promotion invocation runs on the delivery-progress
   scheduler before release.
2. Block a delivery-progress callback; prove reconciliation and password-reset cleanup invoke on their isolated
   schedulers before release.
3. On the two-thread delivery scheduler, block one periodic-style task and prove the second runs; block both and prove
   a third stays queued until a latch releases. Repeat for password-reset maintenance.
4. Scan all production `@Scheduled` annotations and assert the exact approved mapping and fixed-delay properties.
5. Instantiate production scheduler configuration and assert bean names, prefixes, pool sizes, daemon status, queue
   policies, and shutdown policies.
6. Preserve P00's ordinary-context assertion: no annotation processor and zero registered tasks.
7. In a P00 scheduling-opt-in full context, inspect `ScheduledTaskHolder`/scheduled runnables and assert all five real
   callbacks and qualifiers are registered; assert the real scheduler beans have the approved topology.
8. Saturate every worker in the three recurring-business schedulers with callbacks that have already crossed the Relay
   admission boundary and queue an immediately-ready wrapper behind each pool. In a phase-isolated test, explicitly publish the owning
   `ContextClosedEvent`, observe the gate's closed transition and ordinary executor shutdown, then release the
   blockers before calling `context.close()`. Permit the JDK wrappers to execute and assert their business-work latches
   remain untouched and each denial is metered. Close that context to prove termination and no named thread leak. In a
   separate fresh context, keep already-admitted interruptible callbacks running through real `context.close()` and
   prove destruction-time interruption and bounded termination. Also prove post-close scheduler submission rejection.
9. Inventory committed P04/P05/ready-work concurrency evidence first and reuse it. Add PostgreSQL concurrency tests
   only for uncovered scheduled entry semantics, especially password-reset recovery and cleanup. Distinguish redundant
   reset email work from database/security corruption and apply the password-reset stop condition in Section 9.
10. Exercise all three real Spring annotation-routing outcomes: unqualified reaches the fail-fast default, an approved
    qualifier routes successfully amid multiple scheduler beans, and an unknown qualifier fails Spring qualifier
    resolution. The production inventory test also fails on any new unmapped callback.
11. Verify callback metrics with a controllable monotonic time source and `SimpleMeterRegistry`, including success,
    failure, disabled-body success, duration, lag, and fixed low-cardinality tags.
12. Verify success, DNS, policy, connection/TLS, response/read, and timeout/race paths cancel their retained deadline
    future in `finally`; fast completion must remove the pending entry promptly rather than waiting 15 seconds. Verify
    context termination without changing the monotonic total-deadline contract.
13. Unit-test the admission/close boundary with controlled contention in both orders. Latches/barriers and a
    package-private boundary probe pause a contender while it owns the synchronization boundary: admission-first must
    be classified as running work, while close-first must deny the callback and leave its business latch untouched.
    Assert that close never waits for the admitted body and that a denied attempt cannot enter metrics or business work.
14. Close a child application context and prove its propagated event does not close a parent-owned admission gate;
    closing the owning parent does. For password-reset cleanup, prove a denied scheduled wrapper touches neither the
    transaction manager nor repository, while an admitted wrapper retains one atomic delete transaction.

Bounded await timeouts are test failure limits, not evidence of correctness. Ordering is established by latches,
barriers, captured registration, affected-row counts, executor queue state, and committed database state.

## 13. Compatibility and rollout

This is an in-process wiring change with no schema, message, HTTP, retry-tier, cadence, batch-size, or API change. A
rolling deployment may temporarily contain old instances with the shared scheduler and new instances with isolated
schedulers; database correctness remains governed by the existing P04/P05 and ready-work predicates. Progress
isolation improves only on upgraded instances, so deploy all instances before evaluating scheduler-lag telemetry.

The `retryTaskScheduler` bean name disappears. It is not an external protocol. Tests or local configuration that refer
to it must move to the approved names. The static webhook deadline holder disappears; constructors/tests must inject a
scheduler explicitly.

## 14. Rejected alternatives

| Alternative | Reason rejected |
|---|---|
| Increase the single shared pool | Does not express ownership; a growing set of blocking callbacks can consume it, and a future callback silently joins it. |
| One scheduler per method | Provides isolation but adds ownership objects without benefit; two fixed-delay tasks can safely share a two-slot subsystem scheduler. |
| Boot's default scheduler / `@Primary` | Relies on implicit default resolution and cannot classify future jobs. |
| `SchedulingConfigurer` with one registrar scheduler | Forces one default ownership domain and works against per-method documented qualifiers. |
| Programmatic recurring registration | Reimplements annotation lifecycle and complicates P00 suppression/registration inventory without a framework limitation requiring it. |
| Delegate every callback immediately to worker executors | Moves starvation and creates overlapping/multiplicative concurrency unless a second ownership protocol is designed. |
| Virtual-thread scheduler | Fixed-delay behavior and unbounded task concurrency are unnecessary; current callbacks are bounded synchronous maintenance loops. |
| Distributed scheduler leadership | No current job needs singleton invocation for database correctness; it would add a new availability dependency. |
| Scheduler-specific test disable properties | Recreates the per-component omission problem P00 removed and would not protect future callbacks. |
| Keep the static webhook deadline executor | It is not context-owned and cannot satisfy close/leak tests. |
| Treat early executor `shutdown()` as a no-ready-wrapper guarantee | JDK 21 permits an already-due one-shot queued before shutdown to execute when capacity becomes available. |
| Call `shutdownNow()` from the early close event | Changes the approved soft-shutdown/destruction phases and interrupts already-running callbacks earlier than intended. |
| Gate through `ThreadPoolTaskScheduler.setTaskDecorator` | The decorator wraps `RunnableScheduledFuture`; skipping it can break future completion and recurring rescheduling bookkeeping. |
| Put lifecycle state inside `ScheduledJobMetrics` | Mixes context lifecycle ownership with measurement and obscures the business-admission boundary. |

## 15. Explicit invariants

1. Every production `@Scheduled` method names an approved scheduler explicitly.
2. An unqualified production scheduled method fails both architecture tests and application startup.
3. A callback outside delivery progress cannot consume delivery-progress scheduler capacity.
4. Either delivery-progress loop can be blocked without preventing the other loop's invocation.
5. Reconciliation and password-reset maintenance retain invocation capacity when delivery progress is blocked.
6. Scheduler isolation makes no claim about downstream PostgreSQL, RabbitMQ, HTTP, or shared machine contention.
7. Fixed-delay cadence, retry tiers, batch sizes, and business enable flags remain unchanged.
8. P00 ordinary contexts register zero autonomous production callbacks; opt-in contexts use the real topology.
9. P04 execution generations and P05 allocation locking remain the only relevant database authorities.
10. No deployment-wide scheduler leader is required by P06.
11. Scheduler resources are context-owned and reject new scheduler submissions after close begins. Once the
    context-owned Relay admission boundary closes, no scheduled callback that has not already crossed it may begin
    business work; an already-admitted callback is running work under the existing destruction/interruption contract.
12. Scheduler metrics use only fixed low-cardinality scheduler/job/outcome values.
13. The final implementation audit must account for every scheduling and executor facility in the resulting tree.
14. Reconciliation remains one synchronous two-phase callback; P06 does not promise isolation between its phases.
15. Every scheduled HTTP cancellation future is retained and canceled in `finally`; remove-on-cancel provides prompt
    queue removal but does not replace explicit ownership.
16. All five production `@Scheduled` entry methods use `ScheduledCallbackRunner`; webhook deadline callbacks do not
    use the recurring-business admission gate.
17. The scheduled password-reset cleanup transaction begins only after Relay admission; denial performs no transaction
    or repository work, while the direct-call cleanup transaction remains unchanged.

## 16. Unresolved owner decisions

No correctness-blocking product decision remains. Operational owners must choose alert thresholds for invocation lag,
callback failures, and sustained executor saturation after baseline data exists. Separately, product/security owners may
decide whether redundant password-reset recovery emails are acceptable; if not, that is a future database
claim/idempotency project, not a reason to make P06 deployment-singleton.
