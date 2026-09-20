# Task 2 Report: Deterministic Positive Jitter and Promotion-Only RetryScheduler

## Status

Implemented on branch `feat/postgres-retry-scheduler-2026-09-20`.

The scheduler promotes due retry rows through `ReadyWorkRepository.promoteDueScheduled(...)` only. PostgreSQL remains the due-time authority through the existing `CURRENT_TIMESTAMP` and `FOR UPDATE SKIP LOCKED` query. The scheduler performs no RabbitMQ I/O.

## Changed files

- `src/main/java/com/example/relay/deliveryengine/retry/RetryProperties.java`
  - Added the seven `relay.retry` properties and validation.
  - Enforces non-negative jitter/grace, positive intervals/batch sizes/confirm timeout, and `publish-confirm-timeout < unconfirmed-ready-grace`.
- `src/main/java/com/example/relay/deliveryengine/retry/RetryJitterSource.java`
  - Added the injectable jitter-source interface.
- `src/main/java/com/example/relay/deliveryengine/retry/ThreadLocalRetryJitterSource.java`
  - Added uniform nanosecond jitter in the inclusive `[0, maximum]` range.
- `src/main/java/com/example/relay/deliveryengine/retry/RetryDelayCalculator.java`
  - Added injected-clock calculation of `baseDelay + uniform(0, floor(baseDelay * jitterFactor))`.
- `src/main/java/com/example/relay/deliveryengine/retry/RetryScheduler.java`
  - Added fixed-delay promotion using only `ReadyWorkRepository`.
- `src/main/java/com/example/relay/deliveryengine/config/RetrySchedulingConfig.java`
  - Added the dedicated single-thread `relay-retry-` scheduler with graceful shutdown settings.
- `src/main/java/com/example/relay/deliveryengine/worker/RetryTier.java`
  - Retained attempt/delay mappings and `MAX_ATTEMPTS`; removed Rabbit routing-key state.
- `src/main/java/com/example/relay/deliveryengine/worker/DeliveryWorker.java`
  - Kept the transitional pre-Task-4 wait-queue behavior source-compatible by moving its legacy routing-key switch out of `RetryTier`.
- `src/main/resources/application.properties`
  - Added all seven requested `relay.retry` defaults; no global Spring virtual-thread scheduling was enabled.
- `src/test/java/com/example/relay/deliveryengine/retry/RetryDelayCalculatorTest.java`
  - Added deterministic clock/jitter and configuration-validation coverage.
- `src/test/java/com/example/relay/deliveryengine/retry/RetrySchedulerPostgresTest.java`
  - Added due/future promotion coverage and concurrent disjoint-batch coverage against PostgreSQL.
- `src/test/java/com/example/relay/deliveryengine/worker/RetryTierTest.java`
  - Removed obsolete Rabbit routing-key assertions while retaining tier, delay, and max-attempt coverage.

Unrelated untracked prompt/document files and the existing `docs/` work were preserved.

## TDD evidence

### RED

Command:

```text
./mvnw test -Dtest=RetryDelayCalculatorTest,RetrySchedulerPostgresTest
```

Evidence: exit code `1` during test compilation because `RetryDelayCalculator` and `RetryProperties` were absent. This was the expected pre-implementation failure.

### GREEN

Final requested command:

```text
./mvnw test -Dtest=RetryDelayCalculatorTest,RetrySchedulerPostgresTest,RetryTierTest
```

Evidence: exit code `0`; `RetryDelayCalculatorTest` ran 6 tests, `RetrySchedulerPostgresTest` ran 2 tests, and `RetryTierTest` ran 3 tests. Maven reported `Tests run: 11, Failures: 0, Errors: 0` and `BUILD SUCCESS`.

A clean focused run of the same three test classes also completed successfully before the final non-clean verification.

## Configuration validation

The defaults are:

```text
relay.retry.jitter-factor=0.25
relay.retry.scheduler-interval=1s
relay.retry.scheduler-batch-size=100
relay.retry.dispatcher-interval=1s
relay.retry.dispatcher-batch-size=100
relay.retry.unconfirmed-ready-grace=10s
relay.retry.publish-confirm-timeout=5s
```

The tests cover negative jitter/grace, non-positive scheduling and timeout values, and equal confirm/grace durations. The strict timeout ordering is validated with an explicit failure assertion containing `publish-confirm-timeout`.

## Concerns and follow-up boundary

`DeliveryWorker` still publishes retries to the legacy RabbitMQ wait routing keys through an explicit compatibility switch. This is intentionally preserved for the transitional state requested by the plan; Task 4 owns replacing that publication with `RetryDelayCalculator` plus durable dispatcher handoff and removing the wait topology. The Task 2 `RetryScheduler` itself has no publisher dependency and makes no broker call.

The existing `ReconciliationSweeper` promotion bridge remains untouched and safe until Task 4 removes it.
