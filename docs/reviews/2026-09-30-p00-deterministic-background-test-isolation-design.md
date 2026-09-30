# P00 — Deterministic Background Test Isolation

Date: 2026-09-30

Status: investigation and engineering design; no production or test implementation has been changed

## 1. Decision summary

Relay should make autonomous background execution **off by default in every Spring test
ApplicationContext**, independently of the production properties and independently of which test loaded the
context. A test-only Spring `ContextCustomizerFactory` should enforce two orthogonal defaults:

1. do not register `@Scheduled` methods; and
2. do not auto-start Rabbit listener containers.

Tests that genuinely exercise timer registration or message consumption should opt in with the single annotation
`@EnableTestBackgroundExecution(...)`, naming `SCHEDULING`, `RABBIT_LISTENERS`, or both. The annotation itself carries
`@DirtiesContext(classMode = AFTER_CLASS)`; separate scheduling and listener annotations must not be introduced.
Ordinary integration tests should invoke scheduler/sweeper methods directly when timer behavior is not the subject of
the test.

This is a test-only change. Production `@EnableScheduling`, scheduling defaults, Rabbit listener defaults, retry
semantics, SQL, schema, and business behavior remain unchanged.

This project is the concrete implementation and refinement of the readiness roadmap's earlier “P00 — Restore the
repository's verification gate.” Its current name is **P00 — Deterministic Background Test Isolation**. It is not a
second P00, does not become P00b, and does not renumber P01 or later projects.

## 2. Investigation boundaries and evidence

The inventory covered all production Java sources for `@Scheduled`, `@RabbitListener`, scheduler/executor creation,
Spring lifecycle/startup hooks, and asynchronous dispatch. It also covered all Spring test annotations, context
customizers, Testcontainers fixtures, `@DirtiesContext`, test properties, the P03 lifecycle investigation, and the
shared PostgreSQL fixture.

Current topology at this revision:

- 58 `@SpringBootTest` classes;
- 20 `@DataJpaTest` classes;
- 75 integration-tagged classes in total;
- 14 classes using `@DirtiesContext`;
- 19 classes owning a RabbitMQ Testcontainer;
- 8 classes owning a Redis Testcontainer;
- one JVM-static PostgreSQL Testcontainer shared by all participating full and JPA-slice contexts;
- serial integration/full execution, with class parallelism enabled only for the unit tier.

### 2.1 Reproduction evidence gathered for P00

No source was changed for these runs.

Retry-promotion interference was reproduced with a controlled 25 ms cadence in the preceding context:

```bash
./mvnw test \
  -Dtest=ReconciliationPropertiesTest,DeliveryWorkerIntegrationTest \
  -Drelay.retry.scheduler-interval=25ms
```

Result: 24 tests, 6 errors. Every error expected a newly created retry child to remain `SCHEDULED` and observed
`CREATED`. The active foreign thread repeatedly executed `promoteDueScheduled` against the shared database.

Ready-work claim interference was reproduced with a controlled 25 ms dispatcher cadence:

```bash
./mvnw test \
  '-Dtest=ReconciliationPropertiesTest,ReconciliationSweeperIntegrationTest#staleInFlightAttempt_isResetWithoutDirectPublication' \
  -Drelay.retry.dispatcher-interval=25ms
```

Result: 2 tests, 1 error. After the target test reset `IN_FLIGHT → CREATED`, the foreign dispatcher wrote
`ready_dispatch_claim_id=4810230d-9973-4cf9-bacb-43d604316690` before the assertion.

These results agree with the earlier P03 investigation and with its pre-P03 control revision. P03 is not the source of
either transition.

## 3. Current context and infrastructure topology

### 3.1 Spring context caching

Spring caches an ApplicationContext by its merged test configuration. Differences such as `@TestPropertySource`,
`@MockitoBean`, `@Import`, web environment, dynamic properties, and Testcontainers service connections create distinct
cache keys. An ordinary test-class boundary does not close a cached context. Consequently, Relay can retain many full
contexts simultaneously, each with its own:

- Hikari pool;
- scheduled-task registrar and scheduler(s);
- Rabbit listener containers and connection factory;
- ready-publication confirmation executor;
- delivery virtual-thread executor;
- DNS executor and HTTP connection pool.

`@DirtiesContext(AFTER_CLASS)` closes one selected context and its managed lifecycle resources. It has no effect on
other cached contexts. A property on a target test also changes only that target context; it cannot stop an already
cached context.

The context cache is therefore not the defect by itself. The unsafe combination is:

```text
multiple live contexts
        +
autonomous production callbacks in those contexts
        +
one shared mutable infrastructure namespace
```

### 3.2 PostgreSQL

`SharedPostgresContainer` starts one static PostgreSQL 16 container for the test JVM and publishes the same JDBC URL to
every implementing test. Each distinct Spring context creates its own Hikari pool, but all pools address the same
database and `public` schema. Table cleanup is also global rather than context-owned.

This makes every scheduled database query cross-context by construction. Disabling a scheduler in context B does not
prevent a scheduler in cached context A from reading or changing rows created by B.

### 3.3 RabbitMQ

RabbitMQ is not globally shared in the same way. The 19 Rabbit integration classes declare class-owned
`RabbitMQContainer` instances through `@ServiceConnection`. Contexts without a broker are pointed by Surefire at
`localhost:1` so listener startup fails non-fatally and retries.

Rabbit listeners still need separate treatment because:

- a cached listener context owns reconnect/consumer threads until context close;
- a consumer can submit work to the context-owned delivery executor before the class ends;
- in-flight consumer/executor work can mutate the globally shared PostgreSQL database;
- a cached context can outlive the class-scoped Rabbit container and continue reconnect attempts;
- future test-fixture consolidation could make a broker or virtual host shared, turning queue consumption itself into
  cross-context interference.

The default policy must therefore suppress listener startup, not merely point ordinary tests at a dead port.

### 3.4 Redis and other infrastructure

Redis containers are class-owned and used synchronously by rate-limit operations. No production Redis listener,
subscriber, scheduled Redis mutator, or automatically running Redis executor was found. Redis still prevents safe
blanket integration parallelism because tests use `flushAll()` and shared keys, but it is not a current autonomous
background leak.

MockWebServer and TLS fixtures are class/test-owned. They do not start production callbacks. The delivery DNS and HTTP
executors are demand-driven and do not mutate shared state without a listener or direct test invocation.

## 4. Complete production background-component inventory

### 4.1 Autonomous scheduled components

| Component | Enablement and production default | Starts with a full context | Shared-state effects | Tests that require autonomous execution | Accidental exposure and lifetime |
|---|---|---|---|---|---|
| `RetryScheduler` | `relay.retry.scheduling-enabled`; Java default `true`. Cadence `relay.retry.scheduler-interval`, default `1s` | Yes, because `RelayApplication` has `@EnableScheduling` | PostgreSQL `SCHEDULED → CREATED` through `promoteDueScheduled` | Only the focused scheduling smoke needs real timer registration. Repository/behavior tests call `releaseDueRetries()` directly | Every ordinary full context unless the context's retry flag is false. Uses context-owned `retryTaskScheduler`; survives while cached |
| `ReadyWorkDispatcher` | Same `relay.retry.scheduling-enabled=true`; cadence `relay.retry.dispatcher-interval`, default `1s` | Yes | Claims `CREATED` rows in PostgreSQL, publishes Rabbit messages, and later writes confirmation state on `readyWorkConfirmationExecutor` | Focused scheduling smoke. Dispatcher integration tests deliberately call `dispatchOnce()` and do not need the timer | Every ordinary full context unless the retry flag is false. Scheduler plus confirmation tasks survive while cached |
| `ReconciliationSweeper` | `relay.reconciliation.scheduling-enabled`; Java default `true`. Cadence default `30s` | Yes | Resets stale `IN_FLIGHT → CREATED`; touches and republishes unnotified `DEAD` attempts | `DeadLetterNotifierRecoveryIntegrationTest` currently tests the real periodic recovery loop | Every ordinary full context unless the context's reconciliation flag is false. Unqualified schedules use Spring's scheduler and survive while cached |
| `PasswordResetEmailRecoverySweeper` | No enable flag. Cadence `relay.password-reset.email-recovery.interval`, default `60s` | Yes | Reads and retires/reissues password-reset rows, creates a successor token, and publishes email work | `PasswordResetEmailRecoverySweeperIntegrationTest` currently tests the real periodic loop | **All** full contexts, including contexts that disable retry and reconciliation. Its fast 2s test context is already dirtied because it previously leaked |
| `PasswordResetTokenCleanupTask` | No enable flag. Cadence `relay.password-reset.cleanup.interval`, default `1d` | Yes | Deletes old password-reset rows | None; `PasswordResetTokenCleanupTaskTest` invokes `cleanup()` directly | **All** full contexts. The long cadence lowers observed frequency but is not an isolation guarantee |

Important correction to the current test assumptions: `spring.task.scheduling.enabled=false` does not disable these
callbacks in Relay. `RelayApplication` explicitly imports scheduling through `@EnableScheduling`, which registers
Spring's internal `ScheduledAnnotationBeanPostProcessor`. The Boot task-scheduler auto-configuration property does not
remove that explicit registrar. The tests that combine `spring.task.scheduling.enabled=false` with component-specific
flags suppress only the guarded retry/reconciliation methods; the two password-reset schedules remain registered.

### 4.2 Rabbit listeners

| Component / listener ID | Actual container factory | Enablement and default | Starts with a full context | Effects | Tests that require it | Lifetime/leak risk |
|---|---|---|---|---|---|---|
| `DeliveryWorker` / `deliveryWorker` | Explicit `deliveryListenerContainerFactory` | Rabbit listener container auto-start; Spring Boot default `true`. Relay's custom factory calls `SimpleRabbitListenerContainerFactoryConfigurer.configure(...)` | Yes; 4 consumers by default | Claims attempts, performs HTTP/DNS work, persists success/failure/retry rows, and publishes dead-letter work. It submits processing to `virtualThreadExecutor` | Worker integration, acknowledgment, concurrency/capacity, scheduled-isolation, and replay-lifecycle tests | Container consumers plus submitted virtual-thread tasks live until context shutdown; in-flight work can outlive the test method/class |
| `DeadLetterNotifier` / `deadLetterNotifier` | Implicit Boot `rabbitListenerContainerFactory` | Default simple listener auto-start `true` | Yes | Sends email and claims `dead_letter_notified_at` | Dead-letter listener and recovery integration tests | Consumer/reconnect threads survive cached contexts; handler mutates shared PostgreSQL |
| `EmailDispatchConsumer` / `emailDispatchConsumer` | Implicit Boot `rabbitListenerContainerFactory` | Default simple listener auto-start `true` | Yes | Sends email and, for password reset, writes `reset_email_dispatched_at` | Email dispatch/failure integration and the password-reset recovery end-to-end path | Consumer/reconnect threads survive cached contexts; handler mutates shared PostgreSQL |

`spring.rabbitmq.listener.simple.auto-startup=false` correctly suppresses the default simple factory and the custom
delivery factory because both are initialized through Boot's `SimpleRabbitListenerContainerFactoryConfigurer`. In
Spring Boot 3.5.16, the shared abstract configurer applies
`factory.setAutoStartup(configuration.isAutoStartup())`. Thus all three current production listeners are governed by
the proposed property mechanism.

The property is an implementation mechanism, not the architectural contract. The authoritative contract is observed
through `RabbitListenerEndpointRegistry`:

```text
ordinary mode
    -> deliveryWorker, deadLetterNotifier, emailDispatchConsumer all present but not running

RABBIT_LISTENERS opt-in
    -> the intended production listener containers are running
```

The `deliveryWorker` listener is already the practical non-default/custom-factory fixture. A synthetic additional
factory would add disproportionate framework machinery without improving coverage. The inventory guard must enumerate
every production `@RabbitListener`, resolve its actual factory, and fail if any ordinary-mode production container is
running. A future listener/factory that does not inherit the simple-listener setting will therefore be visible even
though P00 does not pre-design support for hypothetical factory types.

### 4.3 Startup and demand-driven lifecycle components

| Component/resource | Automatic activity | Shared-state risk | P00 treatment |
|---|---|---|---|
| `ScheduledStatusConstraintGuard` (`ApplicationRunner`) | Runs once at startup only under the `docker` profile | Read-only PostgreSQL constraint inspection; tests normally clear `SPRING_PROFILES_ACTIVE` | Inventory only; no suppression needed for normal tests. Preserve production behavior |
| RabbitAdmin/topology declarations | Context startup declares exchanges, queues, and bindings if a broker is reachable | Mutates the context's broker namespace, not PostgreSQL | Listener suppression does not need to suppress topology beans. Publisher/config tests can still declare and inspect topology |
| `retryTaskScheduler` | Context-owned `ThreadPoolTaskScheduler`; schedules retry and dispatcher | Drives the known races | No callbacks registered by default; bean may exist idle. Closed with context |
| `readyWorkConfirmationExecutor` | Context-owned `ThreadPoolTaskExecutor`; work begins after dispatch publication | Can update ready-publication state asynchronously | Demand-driven; safe when dispatcher timer/listeners are off. Closed with context |
| `virtualThreadExecutor` | Creates a virtual thread per submitted delivery | Delivery mutation after Rabbit consumption or direct invocation | Demand-driven; real-listener opt-in contexts must close after class |
| delivery DNS executor | Fixed daemon pool, threads created on demand; bean destroy calls `shutdownNow` | Network only, then worker may persist an outcome | Demand-driven; no default suppression beyond listeners |
| Apache connection manager/client | Context-owned, closed on context shutdown | Network only | Demand-driven |
| webhook deadline scheduler | Static daemon single-thread executor, initialized when the production transport is constructed | Cancels HTTP requests only; no autonomous database work; JVM lifetime rather than context lifetime | Record as a process-lifetime resource; no P00 change because it cannot act without a delivery call |

No `@Async`, `@EnableAsync`, `SmartLifecycle` application component, command-line runner, application-event mutator,
Redis subscriber, or additional cleanup job was found.

## 5. Known and latent interference mechanisms

### 5.1 Retry promotion

```text
cached scheduler-enabled context
  -> RetryScheduler tick
  -> promoteDueScheduled uses PostgreSQL CURRENT_TIMESTAMP
  -> target worker's fixed-clock retry row is already due in database time
  -> SCHEDULED becomes CREATED before assertion
```

The target worker context's `relay.retry.scheduling-enabled=false` cannot affect the cached source context.

### 5.2 Ready-work claim

```text
target test manually sweeps stale IN_FLIGHT to CREATED
  -> cached foreign ReadyWorkDispatcher sees globally visible row
  -> claimUnpublishedReady writes ready_dispatch_claim_id/claimed_at
  -> target observes a foreign lease
```

### 5.3 Other latent interference

- `PasswordResetEmailRecoverySweeper` is always scheduled in every full context. It can invalidate a stale row, insert
  a successor row, publish email work, and create foreign-key cleanup failures. One fast-cadence test already documents
  this exact historical leak.
- `PasswordResetTokenCleanupTask` is always scheduled. A one-day cadence makes failures rare, not impossible, and a
  future test cadence override would make the same architecture visible.
- Ordinary full contexts currently start three Rabbit listener containers against `localhost:1`, generating reconnect
  threads and teardown cost even though those tests do not exercise Rabbit.
- A listener test can finish while a delivery task is still executing on its virtual-thread executor unless its
  assertions and context close establish completion.
- Rabbit container shutdown does not itself evict a cached Spring context. A cached listener can continue retrying its
  now-dead endpoint.
- Global table cleanup can race any of the five scheduled database mutators, producing transient FK, optimistic-lock,
  or row-count failures beyond the two P03-observed state assertions.
- If integration-class parallelism is enabled in the future, default background suppression is necessary but not
  sufficient: global PostgreSQL cleanup and Redis `flushAll()` remain cross-test hazards.

## 6. Root cause

The root cause is not a bad retry transition and not a missing assertion tolerance. It is the absence of an ownership
boundary between autonomous work and shared integration-test infrastructure.

Production assumes one application instance is entitled to process any eligible row/message in its deployment.
Tests create multiple application instances but place them in one database namespace. Each instance therefore behaves
correctly from the production algorithm's perspective while violating the test author's implicit ownership assumption.

The test architecture must either remove autonomy by default, close every autonomous context before another owner uses
the namespace, or give each context a separate namespace. Relay currently does none of those consistently.

## 7. Alternatives

| Strategy | Determinism | Real production scheduling/listeners | Accidental future activation | Cache/runtime | Maintenance | Root-cause coverage |
|---|---|---|---|---|---|---|
| A. Disable background execution by default, explicit opt-in | High when enforced centrally | Preserved in a small opt-in set | Low; omission is safe | Maximizes ordinary context reuse; opt-in contexts close | One central mechanism and visible annotations | Solves autonomous mutation for current and future `@Scheduled`/Rabbit listener beans |
| B. Strategic `@DirtiesContext` only | Medium | Preserved | High; every author must remember every source context | More context rebuilds; order-sensitive omissions remain | Distributed annotation burden | Treats context lifetime, but misses forgotten contexts and ordinary listeners |
| C. Schema/container/vhost per context or class | Very high | Preserved | Low if universally provisioned | Highest startup/migration/container cost | Complex Flyway schema, connection, cleanup, Redis namespace, and Rabbit vhost lifecycle | Strongest general isolation, including parallel tests, but disproportionate for serial P00 |
| D. Controlled test scheduler/executor replacement | Very high for component tests | Does not alone prove framework timer/listener wiring | Low inside focused tests | Fast | Requires narrow fakes and a small real-wiring smoke test | Excellent complement; not a suite-wide boundary by itself |

### 7.1 Why not properties alone

Adding all current disable properties to every test is not future-safe. It relies on authors knowing every background
component, does not cover components without enable flags, and does not neutralize explicit `@EnableScheduling`.
Surefire system properties are also unsuitable because system properties outrank class-level opt-in overrides.

### 7.2 Why not `@DirtiesContext` alone

`@DirtiesContext` is useful for a context intentionally allowed to own background threads. It is a poor default safety
mechanism because the dangerous context is often a preceding, unrelated properties/binding test rather than the test
that fails. The annotation belongs on explicit opt-in, not on every victim.

### 7.3 Why not infrastructure isolation first

Per-context PostgreSQL schemas would require unique schema allocation, Flyway migration per context, datasource search
path management, cache-key integration, and teardown. Rabbit would need matching vhost/queue isolation and Redis would
need key/database isolation. That is justified before parallel integration execution or sharding, but it adds large
suite cost to solve a problem that central background suppression removes in the current serial suite.

### 7.4 Role of controlled schedulers

Most scheduler behavior is already better tested by direct calls (`releaseDueRetries`, `dispatchOnce`, `sweep`,
`cleanup`) with real repositories or focused mocks. Keep one minimal real scheduling smoke test and the two real
end-to-end scheduled recovery tests. Use a capturing/controllable test scheduler in P00's cross-context regressions so
the exact foreign callback is released by a latch rather than by a sleep.

## 8. Recommended test architecture

### 8.1 Central default policy

Register a test-only `ContextCustomizerFactory` through
`src/test/resources/META-INF/spring.factories`. It participates in every Spring TestContext without requiring a base
class, active profile, or per-test annotation.

Before creating or modifying that file, inspect both
`src/test/resources/META-INF/spring.factories` and `src/main/resources/META-INF/spring.factories`, plus other
repository-owned Spring factory/import registration files relevant to tests. At this revision none exists. If one is
added before implementation, merge the `ContextCustomizerFactory` registration into it; never overwrite or remove an
unrelated factory registration.

For the default mode it must:

- remove/prevent the internal `ScheduledAnnotationBeanPostProcessor` after configuration-class processing but before
  singleton instantiation, so no `@Scheduled` callback is registered despite production `@EnableScheduling`;
- add a highest-precedence test property forcing
  `spring.rabbitmq.listener.simple.auto-startup=false`; source inspection proves that it controls both current
  production factory paths, while registry assertions remain the authoritative enforcement contract;
- contribute its mode to `equals`/`hashCode`, making enabled and disabled contexts distinct cache keys.

During implementation, narrowly evaluate whether Spring AMQP exposes a stable registry/container-level hook that can
enforce non-running containers more directly without replacing factories or annotation processing. Prefer it only if
it is simpler and equally compatible with explicit opt-in. Do not redesign the architecture for hypothetical future
factories; regardless of mechanism, the registry-state tests decide correctness.

This mechanism should not remove scheduler, listener, repository, or publisher beans. Direct method calls and topology
tests still work. It changes autonomous activation only.

### 8.2 Explicit opt-in

Add the approved test annotation with an explicit component list:

```java
@EnableTestBackgroundExecution(SCHEDULING)
@EnableTestBackgroundExecution(RABBIT_LISTENERS)
@EnableTestBackgroundExecution({SCHEDULING, RABBIT_LISTENERS})
```

The annotation should be inherited/meta-annotation friendly and itself carry
`@DirtiesContext(classMode = AFTER_CLASS)`. The customizer reads the annotation and leaves the selected production
activation path intact. It must not set production properties to different values; test-specific cadence properties
remain ordinary `@TestPropertySource` inputs.

Expected scheduling opt-ins:

- `ScheduledLoopEnabledSmokeTest` — real retry/dispatcher timer wiring;
- `DeadLetterNotifierRecoveryIntegrationTest` — real reconciliation cadence and dead-letter consumption;
- `PasswordResetEmailRecoverySweeperIntegrationTest` — real email-recovery cadence and real Rabbit-backed dispatch,
  using combined `SCHEDULING` and `RABBIT_LISTENERS` opt-in.

Expected Rabbit-listener opt-ins are the tests that prove actual consumption:

- `DeliveryReplayLifecycleIntegrationTest`;
- `DeadLetterNotifierIntegrationTest` and `DeadLetterNotifierRecoveryIntegrationTest`;
- `DeliveryWorkerAckLifecycleTest`;
- both aggregate-capacity worker tests;
- `DeliveryWorkerConcurrencyTest`;
- `DeliveryWorkerInFlightStateTest`;
- `DeliveryWorkerIntegrationTest`;
- `DeliveryWorkerScheduledIsolationIntegrationTest`;
- `EmailDispatchFailureLoggingIntegrationTest`;
- `EmailDispatchIntegrationTest`;
- `EmailVerificationDispatchFailureIntegrationTest`;
- `PasswordResetEmailRecoverySweeperIntegrationTest` — its Rabbit container, mocked `EmailService`, and existing
  send counter establish an intended end-to-end consumer contract. The test must assert one consumer send and a
  dispatch-claimed successor token rather than leaving the counter unused.

Publisher/topology/manual-dispatch tests should remain default-off unless inspection proves a specific assertion needs a
running consumer. In particular, `AttemptPublisherIntegrationTest`, dispatcher tests, and reconciliation tests already
need queues without competing consumers.

### 8.3 Context close and thread ownership

All opt-in contexts close after their class. Context close stops scheduled tasks, listener containers, Hikari,
confirmation executors, delivery executors, DNS executors, Apache resources, and other managed beans before a later
class can reuse shared PostgreSQL.

The static daemon webhook deadline scheduler is not context-managed. It performs no work without an explicit HTTP
delivery call, so it is outside the autonomous-mutation boundary. P00 should document it but not redesign it.

### 8.4 Context-cache implications

Ordinary contexts all receive the same disabled-mode customizer and retain normal cache reuse. Opt-in bits form part of
the cache key, so a background-enabled context cannot be reused as an ordinary context. Opt-in contexts are evicted
after class. This uses `@DirtiesContext` as a centrally guaranteed consequence of explicit autonomy, not as a rule
every test author must remember.

## 9. Deterministic regression design

P00 has two related but distinct regression obligations:

```text
policy contract
ordinary context -> P00 customizer -> no autonomous production scheduled callbacks

historical causal regression
foreign real production callback + shared PostgreSQL + victim row -> foreign mutation
```

The second must not be reduced to another assertion that the scheduled-task registrar is empty.

Add a P00 regression harness that holds two Spring contexts against `SharedPostgresContainer` and supplies a
controllable `TaskScheduler` to the source context. The scheduler captures registered callbacks instead of running
them by wall-clock cadence. A latch/barrier releases the captured production callback only after the victim row is
committed.

For each race, run two deliberate harness modes:

1. a policy-bypassed source context, constructed outside the Spring TestContext customizer path, containing the real
   production component and repository SQL; releasing its captured callback must reproduce and assert the historical
   foreign mutation; and
2. an ordinary P00-governed source context; it must acquire no autonomous callback, and the same victim row must remain
   unchanged.

The policy-bypassed mode is regression evidence, not a supported test mode or a public escape annotation.

### 9.1 Retry family

1. Refresh a policy-bypassed source context with the real `RetryScheduler`, real `ReadyWorkRepositoryImpl`, and a
   capturing scheduler.
2. Refresh a separate victim context against the same PostgreSQL container.
3. Commit a child with status `SCHEDULED` and a database-due `next_retry_at`.
4. Release the captured real retry callback and assert it changes the foreign child to `CREATED`.
5. Repeat with an ordinary P00-governed source context and assert no callback is acquired and the child remains
   `SCHEDULED`.

This proves both the original causal writer and the policy boundary.

### 9.2 Ready-work family

1. Build a policy-bypassed source context with the real `ReadyWorkDispatcher`, real `ReadyWorkRepositoryImpl`, and
   capturing scheduler.
2. In the victim context create a stale `IN_FLIGHT` attempt and call the real reconciliation sweep directly.
3. Confirm the committed reset to `CREATED`.
4. Release the captured real dispatcher callback and assert it writes a foreign `ready_dispatch_claim_id` through the
   production SQL.
5. Repeat with an ordinary P00-governed source context and assert no callback is acquired and
   `ready_dispatch_claim_id`, `ready_dispatch_claimed_at`, and `ready_published_at` remain null.

The harness uses committed transactions, the real production components, and the real repository queries in both
causal paths. Only scheduling cadence is controlled.

The harness must use committed transactions, latches/barriers, and direct state polling. It must not use arbitrary
sleep. Keep the two ordered Maven reproducer commands above as diagnostic evidence, but the new regression harness is
the stable CI gate.

Also add contract tests that prove:

- an ordinary full context contains no active scheduled registrations;
- its Rabbit listener registry contains the production containers but none is running;
- a scheduling opt-in context executes a captured/latched scheduled callback;
- a Rabbit opt-in context starts the intended production listener IDs;
- the custom `deliveryListenerContainerFactory` obeys ordinary default-off and listener opt-in just like the Boot
  default factory;
- the opt-in annotation dirties/closes its context;
- a local `@TestPropertySource(auto-startup=true)` cannot bypass default-off without the marker.

## 10. Clock considerations

`DeliveryWorkerIntegrationTest` replaces the application `Clock` with the fixed instant
`2026-09-20T12:00:00Z`. `RetryDelayCalculator` therefore persists retry due times relative to that instant.
`ReadyWorkRepositoryImpl.promoteDueScheduled`, correctly for current production semantics, compares those rows with
PostgreSQL `CURRENT_TIMESTAMP`. On the 2026-09-30 run the rows were immediately due to a foreign scheduler.

P00 should not change production time semantics. With ordinary schedulers suppressed, the mismatch is harmless in
worker tests because no autonomous SQL promotion occurs. Scheduler/repository tests should choose one explicit model:

- tests of PostgreSQL due selection should construct due/future rows relative to database time, as
  `RetrySchedulerPostgresTest` already does; or
- a controlled end-to-end timer test may use the real system application clock and database time.

Do not combine a historical fixed application clock with a live production scheduler unless the test deliberately
asserts immediate promotion. No database-clock abstraction is required for P00.

## 11. Test-isolation invariants

1. A Spring integration test with no background opt-in registers no production `@Scheduled` callbacks.
2. A Spring integration test with no background opt-in starts no Rabbit listener container.
3. Setting a cadence, enable flag, or listener auto-start property is insufficient to opt in; the explicit annotation
   is the authority.
4. Scheduler/sweeper/listener beans remain injectable and directly invocable while autonomous activation is off.
5. A background-enabled test context is never cache-compatible with an ordinary context.
6. A background-enabled test context is closed after its class, including managed executors and datasource pools.
7. Ordinary tests never depend on class order or on another context having been evicted.
8. Tests of real scheduling/listening prove the real Spring/Rabbit wiring, not only direct method behavior.
9. PostgreSQL remains shared and serial for P00; no test may infer row ownership from that sharing.
10. Production background defaults and behavior remain unchanged.

## 12. Non-goals

- changing retry, reconciliation, delivery, replay, Attempt, or P03 semantics;
- changing database schema or production SQL time semantics;
- implementing P04 execution fencing;
- accepting multiple lifecycle states in assertions;
- adding arbitrary sleeps or Surefire ordering/masking;
- enabling integration parallelism or sharding;
- isolating every context into a schema/container/vhost in P00;
- redesigning scheduler pool allocation (P06);
- refactoring the static HTTP deadline scheduler.

## 13. Resolved design decisions

1. The only opt-in annotation is `@EnableTestBackgroundExecution(...)`, with `SCHEDULING`, `RABBIT_LISTENERS`, or
   combined mode. It carries `@DirtiesContext(AFTER_CLASS)`.
2. `PasswordResetEmailRecoverySweeperIntegrationTest` is an end-to-end scheduled recovery and Rabbit-consumption test.
   It uses combined mode and must assert the consumer send and successor dispatch claim.
3. This project refines the readiness roadmap's original P00 verification-gate work under the current name
   **P00 — Deterministic Background Test Isolation**. Later project numbers do not change.
4. All current production listener factories inherit Boot's simple-listener auto-start setting. Registry/container
   behavior, not the property alone, is the permanent regression contract.
