# P03 full-suite lifecycle-error investigation

Date: 2026-09-30

P03 revision investigated: `6605869` on `feature/p03-outbound-destination-safety`

Pre-P03 control revision: `9044e47`

## Purpose

The uncapped 768-test P03 full-suite run reported four lifecycle timing errors after all focused P03 verification had passed:

- one `ReconciliationSweeperIntegrationTest` error;
- three `DeliveryWorkerIntegrationTest` retry-state errors.

This investigation asked whether P03 caused, exposed, or materially increased those failures. It made no production changes. One test-only scheduler-cadence diagnostic was applied equivalently to the P03 and pre-P03 revisions and was fully reverted.

## Conclusion

The comparison result is **A: reproduces pre-P03 through the same mechanism**.

All four errors were caused by background components in other cached Spring application contexts mutating rows in the PostgreSQL database shared by the integration tests. The precise database writers were identified:

- `ReadyWorkDispatcher` called `ReadyWorkRepositoryImpl.claimUnpublishedReady`, which populated `ready_dispatch_claim_id` after the reconciliation test reset an attempt to `CREATED`;
- `RetryScheduler` called `ReadyWorkRepositoryImpl.promoteDueScheduled`, which promoted retry children from `SCHEDULED` to `CREATED` before the worker assertions.

The same transitions reproduced on pre-P03 revision `9044e47`. P03 changes the duration and phase of delivery execution and can therefore change which assertion lands near a scheduler tick, but the bounded comparison found no evidence that P03 created the lifecycle defect or materially increased its probability. The two P03-specific DNS tests are classified as timing exposure of the pre-existing isolation race, not as P03 transport regressions.

No P03 code change is required. P03 is safe to merge independently of the separately tracked P00 shared-context scheduler-isolation work, with the known qualification that the uncapped full suite remains nondeterministic until that test-infrastructure issue is resolved.

## Exact failures

| Test | Expected | Actual | Concrete writer |
|---|---|---|---|
| `ReconciliationSweeperIntegrationTest.staleInFlightAttempt_isResetWithoutDirectPublication` | Reset attempt has no ready-dispatch lease | `ready_dispatch_claim_id` contained a UUID | `ReadyWorkDispatcher` → `ReadyWorkRepositoryImpl.claimUnpublishedReady` |
| `DeliveryWorkerIntegrationTest.exception_beforeFinalAttempt_marksAttemptFailedAndCreatesRetry` | Retry child remains `SCHEDULED` at assertion | Retry child was `CREATED` | `RetryScheduler` → `ReadyWorkRepositoryImpl.promoteDueScheduled` |
| `DeliveryWorkerIntegrationTest.protectedDestination_isRejectedThroughWorkerLifecycleWithoutOpeningListener[1]` (`protected DNS`) | Retry child remains `SCHEDULED` at assertion | Retry child was `CREATED` | Same promotion path |
| `DeliveryWorkerIntegrationTest.protectedDestination_isRejectedThroughWorkerLifecycleWithoutOpeningListener[2]` (`mixed DNS answers`) | Retry child remains `SCHEDULED` at assertion | Retry child was `CREATED` | Same promotion path |

The reconciliation value was a dispatch-claim UUID, not a ready-publication timestamp. `ready_published_at` is an `Instant`; the observed UUID maps to `ready_dispatch_claim_id`.

## Original-run transition evidence

### Reconciliation failure

For attempt `8bf7de93-...`, the observed sequence was:

1. `21:48:01.493`: reconciliation reset the stale attempt from `IN_FLIGHT` to `CREATED`;
2. `21:48:01.533`: background thread `relay-retry-1` executed the ready-work claim SQL;
3. `21:48:01.535`: the dispatcher attempted publication and failed against the deliberately stale `localhost:1` broker endpoint;
4. the row retained the newly written dispatch-claim UUID, so the assertion no longer observed an unclaimed reset row.

The reset itself was correct. The unexpected state was a subsequent claim performed by another live application context.

### DeliveryWorker failures

The failing retry children were promoted shortly after insertion:

| Child insertion | Promotion | Gap |
|---|---|---:|
| `21:51:16.546` | `21:51:16.555` | 9 ms |
| `21:51:20.539` | `21:51:20.559` | 20 ms |
| `21:51:22.922` | `21:51:23.358` | 436 ms |

The worker tests use a fixed retry clock of `2026-09-20T12:00:00Z`, while the database/test run date was 2026-09-29. Consequently, a newly inserted `SCHEDULED` child was already due according to the real-clock scheduler running in another context. `promoteDueScheduled` legitimately changed it to `CREATED` before the test assertion.

## Shared-context evidence

The failing run had at least three scheduler-enabled cached Spring contexts sharing the same PostgreSQL container:

- `DeliveryListenerPropertiesTest`, context `@6c86f6fb`;
- `DeliveryListenerPropertiesConfigurationKeysTest`, context `@35b536c`;
- `ReconciliationPropertiesTest`, context `@1ab95fec`.

`SharedPostgresContainer` deliberately supplies the same database to these contexts. The worker-test context disabling its own scheduler therefore does not disable scheduler loops already started by other cached contexts.

The original logs do not distinguish which of the scheduler-enabled contexts won a particular database race because the contexts use identical thread names. They do establish the exact SQL writer. The ordered reproducers establish that a preceding scheduler-enabled cached context is sufficient.

## Isolated controls

Both affected classes passed independently on the P03 branch:

```text
ReconciliationSweeperIntegrationTest: 8/8 passed
DeliveryWorkerIntegrationTest:        23/23 passed
```

Representative commands:

```bash
./mvnw test -Dtest=ReconciliationSweeperIntegrationTest
./mvnw test -Dtest=DeliveryWorkerIntegrationTest
```

Isolated success was treated only as a control. It did not by itself establish P03 innocence.

## Ordered reproducers and pre-P03 comparison

### DeliveryWorker family

The unmodified ordered sequence was:

```bash
./mvnw test -Dtest=ReconciliationPropertiesTest,DeliveryWorkerIntegrationTest
```

| Revision | Result | Mechanism |
|---|---|---|
| P03 `6605869` | Lifecycle-state errors in 2 of 3 full-class runs | Background `promoteDueScheduled`: `SCHEDULED` → `CREATED` |
| Pre-P03 `9044e47` | Lifecycle-state errors in 2 of 3 full-class runs | Same production transition |

The individual worker method that lost the race varied with scheduler phase. That variation is expected for a periodic background loop and does not indicate distinct failure mechanisms.

### Reconciliation family

To turn the lower-frequency reconciliation race into a high-reliability diagnostic, `ReconciliationPropertiesTest` was temporarily given a 25 ms retry-dispatcher interval. The property was applied equivalently to both revisions; no production code was copied between revisions.

The sequence `ReconciliationPropertiesTest` followed by `staleInFlightAttempt_isResetWithoutDirectPublication` failed 3 of 3 times on P03 and 3 of 3 times on `9044e47`. Each failure followed the same reset-then-claim path. The temporary test property was fully reverted after the comparison.

## P03 interaction analysis

The P03 Apache transport, DNS executor, `DeliveryDeadlineContext`, and bounded response consumer operate before the worker's retry persistence transaction. They do not perform either unexpected database transition:

- retry-child insertion and the existing worker transaction remain the owner of the initial `SCHEDULED` state;
- `promoteDueScheduled` is an external, post-commit scheduler action;
- `claimUnpublishedReady` is an external dispatcher action after reconciliation resets a row to `CREATED`;
- listener acknowledgement timing does not write either unexpected field/state.

Replacing the JDK/RestClient path with synchronous Apache execution can shift wall-clock timing relative to a periodic scheduler. That can change which test observes the race. The equivalent pre-P03 reproductions, matching writers, and similar bounded reproduction rate do not support a conclusion that P03 materially increased the underlying race probability.

## Classification

| Failure | Classification | Evidence |
|---|---|---|
| Reconciliation reset/claim error | Pre-existing defect/test-isolation race | Exact reset→claim writer identified; identical 3/3 pre-P03 reproduction |
| Existing exception-path retry error | Pre-existing defect/test-isolation race | Same promotion writer and 2/3 ordered reproduction on both revisions |
| Protected-DNS retry error | P03 timing exposure of pre-existing defect | Test is P03-specific, but unexpected state is written by the pre-existing scheduler path reproduced on the base revision |
| Mixed-DNS retry error | P03 timing exposure of pre-existing defect | Same as protected-DNS case |

No URI-policy, DNS-to-socket, proxy, TLS, exact-request-byte, bounded-response, deadline, or modeled-failure assertion failed in this investigation.

## Durable implications

- Do not weaken the lifecycle assertions to accept both `SCHEDULED` and `CREATED`.
- Do not add sleeps, disable production scheduling, or change P03 transport semantics to mask the interference.
- Resolve the shared-context/shared-database scheduler isolation under P00, not P03.
- Until P00 is resolved, interpret uncapped full-suite lifecycle timing failures using their concrete database writer and transition rather than attributing them from the final observed state alone.
- No diagnostic instrumentation or production changes from this investigation remain in the worktree.

## P00 resolution — deterministic background test isolation

P00 implemented the test-infrastructure fix described by the
[P00 design](../2026-09-30-p00-deterministic-background-test-isolation-design.md) and
[implementation plan](../../superpowers/plans/2026-09-30-p00-deterministic-background-test-isolation.md).
Ordinary Spring test contexts now register no autonomous production scheduled callbacks and keep every production
Rabbit listener container present but stopped. Tests that genuinely exercise those framework paths opt in through
`@EnableTestBackgroundExecution(SCHEDULING)`, `RABBIT_LISTENERS`, or both; the same annotation evicts and closes the
enabled context after its class.

The deterministic causal regression retains both sides of the historical evidence:

- policy-bypassed source contexts capture and run the real `RetryScheduler` and `ReadyWorkDispatcher` callbacks
  against the real `ReadyWorkRepositoryImpl` PostgreSQL SQL, reproducing `SCHEDULED → CREATED` promotion and the
  foreign ready-dispatch claim;
- equivalent ordinary P00-governed source contexts capture zero callbacks and leave both committed victim rows
  unchanged;
- an opt-in lifecycle contract observes scheduler disposal, datasource shutdown, and context close after the class.

Verification on 2026-09-30 produced:

| Gate | Result |
|---|---|
| Focused P00 policy/causal/scheduling contracts | 12 passed; 0 failures/errors/skips |
| Integration tier | 284 passed; 0 failures/errors/skips |
| Full suite | 779 passed; 0 failures/errors/skips; 8m22s |
| Ordered retry-promotion sequence | 3/3 passed |
| Ordered reset-then-claim sequence | 3/3 passed |
| Production-scope audit | no `src/main` changes |
| `git diff --check` | passed |

The repository-wide `spotless:check` remains red on 169 pre-existing files (including unchanged `src/main` files).
Every P00-touched Java file was formatted with Spotless, but fixing that global baseline would violate P00's explicit
production-scope boundary. This formatting debt is independent of the now-green functional verification gate and is
retained as an explicit release-check exception rather than hidden by reformatting production code in P00.
