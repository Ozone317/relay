# Task 3 report: ReadyWorkDispatcher and confirmed persistent publication

## Result

Task 3 is implemented and verified. The dispatcher claims one database batch per tick, starts asynchronous RabbitMQ publication for each claimed attempt, and performs the fenced confirmation update only after the publisher future completes on the dedicated confirmation executor.

## TDD evidence

Tests were added before the production implementation.

RED command:

```text
./mvnw test -Dtest=ReadyWorkDispatcherIntegrationTest,AttemptPublisherIntegrationTest
```

Exact relevant Maven output:

```text
[ERROR] /home/daksh/Personal/relay/src/test/java/com/example/relay/deliveryengine/dispatcher/ReadyWorkDispatcherIntegrationTest.java:[18,50] cannot find symbol
  symbol:   class ReadyPublishOutcome
  location: package com.example.relay.deliveryengine.publisher
[ERROR] /home/daksh/Personal/relay/src/test/java/com/example/relay/deliveryengine/dispatcher/ReadyWorkDispatcherIntegrationTest.java:[19,50] cannot find symbol
  symbol:   class ReadyTaskPublisher
  location: package com.example.relay.deliveryengine.publisher
[INFO] 8 errors
[INFO] BUILD FAILURE
```

The complete RED log was captured at `/tmp/relay-task3-red.log`.

Final GREEN command:

```text
./mvnw test -Dtest=ReadyWorkDispatcherIntegrationTest,AttemptPublisherIntegrationTest,RabbitMqConfigIntegrationTest
```

Exact final output summary:

```text
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 16.11 s -- in com.example.relay.deliveryengine.publisher.AttemptPublisherIntegrationTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 7.094 s -- in com.example.relay.deliveryengine.dispatcher.ReadyWorkDispatcherIntegrationTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.016 s -- in com.example.relay.deliveryengine.config.RabbitMqConfigIntegrationTest
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  29.733 s
[INFO] Finished at: 2026-09-20T15:48:52+05:30
```

The complete final log was captured at `/tmp/relay-task3-final-postcleanup.log`.

The first restart-test implementation used Testcontainers `stop()`/`start()`. Evidence from the failed run showed a new RabbitMQ container identity, so the test was exercising broker replacement rather than an in-place node restart and the persisted queue state was unavailable. The test now uses `rabbitmqctl stop_app` followed by `rabbitmqctl start_app` in the same pinned container, preserving broker data. The receive assertion uses Awaitility with a 15-second bound and ignores transient receive exceptions; this is specifically for the observed Rabbit client `Connection refused` reconnect window. The final run passed the restart test.

## Changed files

- `src/main/java/com/example/relay/deliveryengine/dispatcher/ReadyWorkDispatcher.java`: scheduled one-batch claim and asynchronous publication/confirmation handling.
- `src/main/java/com/example/relay/deliveryengine/publisher/ReadyPublishOutcome.java`: confirmed, definite-failure, and ambiguous outcomes.
- `src/main/java/com/example/relay/deliveryengine/publisher/ReadyTaskPublisher.java`: ready-publication interface returning `CompletableFuture<ReadyPublishOutcome>`.
- `src/main/java/com/example/relay/deliveryengine/publisher/AttemptPublisher.java`: persistent task publication with correlated confirms, mandatory-return handling, and the five-second confirmation timeout.
- `src/main/java/com/example/relay/deliveryengine/config/RetrySchedulingConfig.java`: dedicated `readyWorkConfirmationExecutor`.
- `src/test/java/com/example/relay/deliveryengine/dispatcher/ReadyWorkDispatcherIntegrationTest.java`: real Postgres/RabbitMQ dispatcher, fencing, batching, recovery, non-blocking, and single-worker-claim coverage.
- `src/test/java/com/example/relay/deliveryengine/publisher/AttemptPublisherIntegrationTest.java`: persistent message, confirm, mandatory return, durable queue, and broker-node restart coverage.
- `src/test/java/com/example/relay/deliveryengine/config/RabbitMqConfigIntegrationTest.java`: pinned RabbitMQ test image for repeatable broker configuration verification.
- This report.

`RabbitMqConfig.java` itself was not changed: it already declared the durable task queue/binding and global confirm/return logging callbacks, while `application.properties` already enabled correlated confirms, publisher returns, and mandatory publication. The new per-message correctness is implemented through `CorrelationData` in `AttemptPublisher`.

## Transaction boundaries

`ReadyWorkDispatcher.dispatchOnce()` completes `claimUnpublishedReady(...)` before any RabbitMQ call. The repository claim method is its own short `@Transactional` JDBC transaction. RabbitMQ send and confirmation waiting occur after that transaction has returned, with no database transaction held across I/O or the five-second confirm timeout.

Only `handleOutcome(...)` on `readyWorkConfirmationExecutor` calls `markReadyPublished(...)`; that method is a separate short `@Transactional` JDBC transaction fenced by both `attempt_id` and the batch `ready_dispatch_claim_id`. A zero-row update means the claim was lost and does not mark the attempt.

Definite failure, ambiguous confirmation, publisher exceptions, and confirmation-handler failures leave `ready_published_at` null. The claim remains recoverable after the configured ten-second grace period. The existing Task 1 reconciliation bridge remains intentionally untouched for Task 4.

## Executor configuration

The `readyWorkConfirmationExecutor` is a `ThreadPoolTaskExecutor` with:

- core pool size: 2
- maximum pool size: 4
- queue capacity: 1,000
- thread prefix: `relay-ready-confirm-`
- graceful shutdown enabled with a five-second await period

The dispatcher never calls `Future.get`, `join`, or `waitForConfirms`; a tick leases one batch and returns. The publisher uses `RetryProperties.publishConfirmTimeout` (5 seconds), which remains strictly below `unconfirmedReadyGrace` (10 seconds).

## Concerns

- The old direct `AttemptPublisher.publish(...)` compatibility path and Task 1 reconciliation bridge remain in place deliberately; Task 4 owns their removal/wiring changes.
- The restart test intentionally tolerates only the RabbitMQ client reconnect window; the broker restart remains an in-place node restart and the message is still required to be received as persistent after recovery.
- The repository-wide `./mvnw spotless:check` remains red on 126 pre-existing files. No unrelated formatting was changed. `git diff --check` is clean for this task.
- Integration contexts still emit background retry/reconciliation SQL debug logs because explicit scheduling configuration is present; the focused tests pass and this is outside Task 3 scope.
