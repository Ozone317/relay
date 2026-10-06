# Task 3 report: Context-own webhook deadlines

## Status

Implemented and committed as `fix: bind webhook deadlines to context lifecycle`.

## RED evidence

The lifecycle tests were authored before the transport changes. The first requested RED command,
`./mvnw -q test -Dtest=ProductionSchedulerLifecycleTest,ApacheWebhookHttpTransportDeadlineTest`, failed during
test compilation because the existing production transport accepted only `ScheduledExecutorService` and had no
`TaskScheduler` constructor. The initial test draft also used an inaccessible package method and a nonexistent Spring
getter; those test-only issues were corrected before continuing. The RED result therefore showed the intended missing
injection seam, though it was a compile-time API failure rather than a behavioral assertion failure.

## Implementation

- `ApacheWebhookHttpTransport` now requires `TaskScheduler` in production and maps the monotonic
  `DeliveryDeadline.remaining()` to `deadlineScheduler.getClock().instant().plus(remaining)` for Spring scheduling.
- The returned non-null `ScheduledFuture<?>` remains local to `post` and is canceled with `cancel(false)` in the
  existing `finally`, covering success and all terminal exceptions after scheduling.
- Removed the static `DeadlineSchedulerHolder` and the two-argument production constructor. Package-private
  executor-based constructors adapt low-level tests with `ConcurrentTaskScheduler`.
- The production HTTP bean and Spring test transport configurations inject the named `webhookDeadlineTaskScheduler`.
- Lifecycle coverage checks dedicated pool size/prefix/daemon behavior, scheduler shutdown policies, termination,
  queued-task cleanup and rejection after close. Transport ownership coverage checks success, DNS, destination policy,
  connection, TLS, response/read failure and timeout cancellation; fast success returns the queue to baseline without
  waiting for the full deadline.
- Existing `ProductionSchedulerConfig` already supplied the shared scheduler bean with the required
  `removeOnCancelPolicy=true`; no config change was needed in this task.

## GREEN evidence

The requested six-class suite passed with the repository's documented test-only environment values because the
unqualified Maven invocation cannot resolve `JWT_SECRET` for the Spring integration contexts:

```text
ProductionSchedulerLifecycleTest                  4 tests, 0 failures, 0 errors
ApacheWebhookHttpTransportDeadlineTest            13 tests, 0 failures, 0 errors
ApacheResponseConsumptionIntegrationTest           5 tests, 0 failures, 0 errors
ApacheWebhookHttpTransportTest                     2 tests, 0 failures, 0 errors
DeliveryWorkerIntegrationTest                    24 tests, 0 failures, 0 errors
DeliveryReplayLifecycleIntegrationTest             3 tests, 0 failures, 0 errors
Total                                               51 tests, 0 failures, 0 errors
```

Command used:

```bash
env JWT_SECRET=0123456789abcdef0123456789abcdef RELAY_EMAIL_SENDER_EMAIL=test@example.com RELAY_EMAIL_SENDER_NAME=RelayTest BREVO_API_KEY=test-api-key ./mvnw -q test -Dtest=ProductionSchedulerLifecycleTest,ApacheWebhookHttpTransportDeadlineTest,ApacheResponseConsumptionIntegrationTest,ApacheWebhookHttpTransportTest,DeliveryWorkerIntegrationTest,DeliveryReplayLifecycleIntegrationTest
```

After a small test cleanup, the focused lifecycle/deadline pair also passed (17 tests, no failures/errors). `git diff
--check` passed.

## Adversarial/self-review

- Scheduling rejection occurs before a handle is returned; no future is retained or canceled in that case.
- Once a handle is returned, the `try/finally` covers normal completion, checked failures, runtime failures and errors.
- Timeout authority and all component checks continue to use monotonic `DeliveryDeadline`; scheduler wall-clock time is
  used only to express the relative remaining duration as an `Instant` for Spring's API.
- Explicit cancellation remains present even though the shared scheduler removes canceled tasks promptly.
- All construction sites were reviewed with `rg`; production and Spring integration sites inject the bean, while
  low-level executor tests own and close their local schedulers.
- No HTTP, timeout budget, retry, SSRF or business response semantics were changed.

## Concerns

The requested Maven command without environment settings fails because `JWT_SECRET` is not set in this checkout.
With the documented test-only environment values, all requested classes pass. No code concern remains.

## Fix round 1: Spring context ownership

The policy change described in this historical round was superseded by Fix round 2 below. The deadline scheduler no
longer has `acceptTasksAfterContextClose=true`; all four real schedulers now use the shared explicit false/false policy.

The original lifecycle test manually invoked `scheduler.shutdown()`, so it did not prove that the owning Spring context
closed the scheduler. It was replaced with a two-context lifecycle test. The first context's active scheduler runs a
controlled interruptible task; closing the context must interrupt that task, terminate the executor, empty its queue,
and reject later scheduling. A second context's scheduler remains operational until its own context closes. Cleanup
assertions use executor state and latches; daemon status is asserted only as a thread policy.

### RED

Before changing scheduler policy, the strengthened test failed at the intended ownership assertion:

```text
./mvnw -q test -Dtest=ProductionSchedulerLifecycleTest#contextCloseOwnsAndIsolatesEachDeadlineScheduler
Tests run: 1, Failures: 1, Errors: 0
AssertionFailedError: closing the owning context must interrupt its running deadline task
```

Setting `waitForTasksToCompleteOnShutdown(false)` alone still failed this assertion because Spring's coordinated
lifecycle stop waits for executing work before bean destruction. The deadline scheduler now defers its close to bean
destruction (`acceptTasksAfterContextClose=true`) and uses interrupting shutdown (`waitForTasksToCompleteOnShutdown=false`).
This setting is scoped to the webhook deadline scheduler; the other production schedulers retain their existing policy.

### GREEN

The strengthened ownership test passed after the scoped scheduler policy update. The focused pair passed:

```text
./mvnw -q test -Dtest=ProductionSchedulerLifecycleTest,ApacheWebhookHttpTransportDeadlineTest
17 tests, 0 failures, 0 errors
```

The full six-class selection passed again with documented test environment values:

```text
ProductionSchedulerLifecycleTest                   4 tests, 0 failures, 0 errors
ApacheWebhookHttpTransportDeadlineTest             13 tests, 0 failures, 0 errors
ApacheResponseConsumptionIntegrationTest            5 tests, 0 failures, 0 errors
ApacheWebhookHttpTransportTest                      2 tests, 0 failures, 0 errors
DeliveryWorkerIntegrationTest                     24 tests, 0 failures, 0 errors
DeliveryReplayLifecycleIntegrationTest              3 tests, 0 failures, 0 errors
Total                                                51 tests, 0 failures, 0 errors
```

`git diff --check` passed.

## Fix round 3: explicit per-scheduler shutdown flag topology

Added a separate `allRealSchedulersUseApprovedShutdownFlags` test that creates the real Spring scheduler context and
checks both Spring shutdown flags for every scheduler, independently of the context-close behavior test. Spring
6.2.19 exposes setters but no public getters for these flags, so the focused test helper reflects only the two
`ExecutorConfigurationSupport` fields. The production factory remains unchanged and sets both flags to false.

### RED

The independent topology test was run with each production flag temporarily set to `true`, restoring the flag after
each run. Both runs failed at their intended per-scheduler assertion:

```text
./mvnw -q test -Dtest=ProductionSchedulerLifecycleTest#allRealSchedulersUseApprovedShutdownFlags
acceptTasksAfterContextClose=true:
  Tests run: 1, Failures: 1, Errors: 0
  AssertionFailedError: deliveryProgressTaskScheduler accepts tasks after context close
waitForTasksToCompleteOnShutdown=true:
  Tests run: 1, Failures: 1, Errors: 0
  AssertionFailedError: deliveryProgressTaskScheduler waits for tasks to complete during shutdown
```

### GREEN

Focused lifecycle and topology classes:

```text
./mvnw -q test -Dtest=ProductionSchedulerLifecycleTest,ProductionSchedulerTopologyTest
ProductionSchedulerLifecycleTest: 5 tests, 0 failures, 0 errors
ProductionSchedulerTopologyTest:   5 tests, 0 failures, 0 errors
```

Task 1 topology/progress/smoke selection:

```text
./mvnw -q test -Dtest=ProductionSchedulerTopologyTest,ScheduledProgressIsolationTest,ScheduledLoopEnabledSmokeTest
14 tests, 0 failures, 0 errors
```

The full Task 3 six-class selection passed with the previously documented test-only environment values:

```text
ProductionSchedulerLifecycleTest                   5 tests, 0 failures, 0 errors
ApacheWebhookHttpTransportDeadlineTest             13 tests, 0 failures, 0 errors
ApacheResponseConsumptionIntegrationTest            5 tests, 0 failures, 0 errors
ApacheWebhookHttpTransportTest                      2 tests, 0 failures, 0 errors
DeliveryWorkerIntegrationTest                     24 tests, 0 failures, 0 errors
DeliveryReplayLifecycleIntegrationTest              3 tests, 0 failures, 0 errors
Total                                                52 tests, 0 failures, 0 errors
```

Exact command:

```bash
env JWT_SECRET=0123456789abcdef0123456789abcdef RELAY_EMAIL_SENDER_EMAIL=test@example.com RELAY_EMAIL_SENDER_NAME=RelayTest BREVO_API_KEY=test-api-key ./mvnw -q test -Dtest=ProductionSchedulerLifecycleTest,ApacheWebhookHttpTransportDeadlineTest,ApacheResponseConsumptionIntegrationTest,ApacheWebhookHttpTransportTest,DeliveryWorkerIntegrationTest,DeliveryReplayLifecycleIntegrationTest
```

No production behavior changed in this round. `git diff --check` passed.

## Fix round 2: shared scheduler close policy and Spring shutdown phases

The approved invariant is now set once in the shared scheduler factory for every real scheduler:
`acceptTasksAfterContextClose=false` and `waitForTasksToCompleteOnShutdown=false`. The webhook deadline bean has no
per-bean override. The lifecycle test creates two Spring contexts and checks all four real schedulers in the owning
context. At context close it proves each executor rejects new scheduling before bean destruction, each queued delayed
future is canceled and never runs, and each active interruptible task is interrupted during bean destruction. The
executor then terminates. Meanwhile, the second context continues to schedule work successfully. A test-only
`DefaultLifecycleProcessor` timeout of 1500ms lets the test reach bean destruction deterministically; it does not alter
production shutdown policy.

### RED

Against the incorrect lifecycle flags from the previous round, the strengthened test failed before bean destruction:

```text
./mvnw -q test -Dtest=ProductionSchedulerLifecycleTest#contextCloseOwnsAndIsolatesEachDeadlineScheduler
Tests run: 1, Failures: 1, Errors: 0
AssertionFailedError: all real schedulers must reject new work as context close begins
```

The policy was then moved to the shared factory and made explicitly false for all four production schedulers.

### GREEN

Focused lifecycle/deadline pair:

```text
./mvnw -q test -Dtest=ProductionSchedulerLifecycleTest,ApacheWebhookHttpTransportDeadlineTest
17 tests, 0 failures, 0 errors
```

Task 1 topology/progress selection, including the scheduled-loop smoke fixture:

```text
./mvnw -q test -Dtest=ProductionSchedulerTopologyTest,ScheduledProgressIsolationTest,ScheduledLoopEnabledSmokeTest
14 tests, 0 failures, 0 errors
```

The tests exposed that Task 2's metrics dependency had left the two Task 1 bare Spring fixtures without a
`MeterRegistry`. Added a test-local `SimpleMeterRegistry` in each fixture; application behavior was not changed.

Full Task 3 selection, with documented test-only environment values:

```text
ProductionSchedulerLifecycleTest                   4 tests, 0 failures, 0 errors
ApacheWebhookHttpTransportDeadlineTest             13 tests, 0 failures, 0 errors
ApacheResponseConsumptionIntegrationTest            5 tests, 0 failures, 0 errors
ApacheWebhookHttpTransportTest                      2 tests, 0 failures, 0 errors
DeliveryWorkerIntegrationTest                     24 tests, 0 failures, 0 errors
DeliveryReplayLifecycleIntegrationTest              3 tests, 0 failures, 0 errors
Total                                                51 tests, 0 failures, 0 errors
```

Exact command:

```bash
env JWT_SECRET=0123456789abcdef0123456789abcdef RELAY_EMAIL_SENDER_EMAIL=test@example.com RELAY_EMAIL_SENDER_NAME=RelayTest BREVO_API_KEY=test-api-key ./mvnw -q test -Dtest=ProductionSchedulerLifecycleTest,ApacheWebhookHttpTransportDeadlineTest,ApacheResponseConsumptionIntegrationTest,ApacheWebhookHttpTransportTest,DeliveryWorkerIntegrationTest,DeliveryReplayLifecycleIntegrationTest
```

`git diff --check` passed.
