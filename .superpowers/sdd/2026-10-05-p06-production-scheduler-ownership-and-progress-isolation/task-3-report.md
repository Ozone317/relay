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
