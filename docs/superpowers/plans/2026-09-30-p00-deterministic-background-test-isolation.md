# P00 — Deterministic Background Test Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make production schedulers and Rabbit listeners inactive by default in Spring tests, with explicit, self-closing opt-in contexts and deterministic regressions for both P03-discovered cross-context races.

**Architecture:** A test-only Spring `ContextCustomizerFactory`, loaded for every Spring TestContext, removes scheduled-method registration and keeps every production Rabbit listener container stopped unless `@EnableTestBackgroundExecution(...)` explicitly enables a capability. The annotation supports `SCHEDULING`, `RABBIT_LISTENERS`, or combined mode and carries `@DirtiesContext(AFTER_CLASS)`. Most component tests continue to invoke jobs directly; a small opt-in set retains real timer/listener coverage.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring TestContext Framework, Spring Scheduling, Spring AMQP, JUnit Jupiter 5, Testcontainers PostgreSQL/RabbitMQ, Awaitility.

**Spec:** `docs/reviews/2026-09-30-p00-deterministic-background-test-isolation-design.md`

## Global Constraints

- Make test-only changes; do not change files under `src/main`.
- Preserve production scheduling/listener defaults and all business behavior.
- Keep `SharedPostgresContainer` and serial integration execution for P00.
- Do not change schema, migrations, retry semantics, Attempt states, P03, or P04 behavior.
- Do not weaken lifecycle assertions, add arbitrary sleeps, or mask tests through Surefire ordering/configuration.
- Use committed transactions and latches/controllable schedulers for cross-context regression coordination.
- Treat this project as the concrete refinement of the readiness roadmap's original P00 verification-gate work; use
  **P00 — Deterministic Background Test Isolation** without P00b or renumbering later projects.
- Preserve all unrelated tracked and untracked work in the existing checkout.

## Review Focus

- A future `@Scheduled` bean without its own enable flag must still be default-off in tests; Task 1's registrar-level contract covers it.
- A future listener or factory escaping the current Rabbit property must fail the ordinary-mode registry assertion; Task 1 inventories factories and pins container behavior rather than only property precedence.
- Scheduling-only, listener-only, and combined opt-in contexts must have different cache identities; Task 1 tests customizer equality/modes.
- Background-enabled contexts must close managed threads even when a test fails; Task 2 makes closure annotation-driven and verifies a close latch.
- Publisher/topology tests must retain usable Rabbit declarations while consumers stay stopped; Task 3 verifies those focused suites.

---

## File map

**Create**

- `src/test/java/com/example/relay/support/background/EnableTestBackgroundExecution.java` — explicit capability marker and automatic `AFTER_CLASS` context eviction.
- `src/test/java/com/example/relay/support/background/TestBackgroundComponent.java` — `SCHEDULING` and `RABBIT_LISTENERS` enum.
- `src/test/java/com/example/relay/support/background/BackgroundExecutionContextCustomizerFactory.java` — global default-off policy and cache-key mode.
- `src/test/resources/META-INF/spring.factories` — creates or safely merges the customizer-factory registration for all Spring tests.
- `src/test/java/com/example/relay/support/background/BackgroundExecutionContextPolicyTest.java` — fast context contracts for default-off and opt-in behavior.
- `src/test/java/com/example/relay/support/background/BackgroundExecutionCrossContextRegressionTest.java` — controllable-scheduler PostgreSQL regressions for promotion and claim interference.

**Modify**

- The three scheduling-loop integration tests listed in Task 2.
- The real Rabbit-consumer integration tests listed in Task 3.
- Tests with redundant local scheduling/listener disable properties listed in Task 4.
- `docs/reviews/probes/2026-09-30-p03-full-suite-lifecycle-investigation.md` — append the P00 regression resolution after implementation evidence exists.

---

### Task 1: Establish the global test-context policy

**Files:**

- Create: `src/test/java/com/example/relay/support/background/TestBackgroundComponent.java`
- Create: `src/test/java/com/example/relay/support/background/EnableTestBackgroundExecution.java`
- Create: `src/test/java/com/example/relay/support/background/BackgroundExecutionContextCustomizerFactory.java`
- Create or modify: `src/test/resources/META-INF/spring.factories`
- Create: `src/test/java/com/example/relay/support/background/BackgroundExecutionContextPolicyTest.java`

**Interfaces:**

- Produces: `@EnableTestBackgroundExecution(TestBackgroundComponent...)`.
- Produces: a customizer mode value containing immutable `schedulingEnabled` and `rabbitListenersEnabled` booleans.
- Consumes: Spring's internal scheduled-processor bean name from `TaskManagementConfigUtils` and
  `spring.rabbitmq.listener.simple.auto-startup`.

- [ ] **Step 1: Inspect and preserve repository Spring factory registrations**

Inspect before editing:

```bash
for file in src/test/resources/META-INF/spring.factories src/main/resources/META-INF/spring.factories; do
  if test -f "$file"; then
    echo "### $file"
    sed -n '1,240p' "$file"
  fi
done
find src -type f \( -path '*/META-INF/spring.factories' -o -path '*/META-INF/spring/*' \) -print
```

At plan-writing time no repository-owned factories/import registration file exists. Re-run the inspection at
implementation time. If `src/test/resources/META-INF/spring.factories` then exists, merge the
`ContextCustomizerFactory` implementation class into the existing comma-separated/backslash-continued value. Preserve
every unrelated key and implementation class. Never replace the whole file blindly.

- [ ] **Step 2: Inventory production Rabbit listener factories**

Record these current mappings in `BackgroundExecutionContextPolicyTest` diagnostics:

```text
deliveryWorker        -> deliveryListenerContainerFactory
deadLetterNotifier    -> rabbitListenerContainerFactory
emailDispatchConsumer -> rabbitListenerContainerFactory
```

Verify from Boot 3.5.16 source that both factories pass through
`SimpleRabbitListenerContainerFactoryConfigurer`, whose shared configuration calls
`factory.setAutoStartup(configuration.isAutoStartup())`. The existing delivery factory is the custom/non-default
factory fixture; do not add an artificial second custom factory.

- [ ] **Step 3: Add failing default-policy contract tests**

Create contexts around a minimal configuration containing one `@Scheduled` probe and one `@RabbitListener` probe.
For the full production context, obtain `RabbitListenerEndpointRegistry` and assert that the containers with IDs
`deliveryWorker`, `deadLetterNotifier`, and `emailDispatchConsumer` are present and `isRunning()` is false. Add a
nested configuration with `@TestPropertySource(properties =
"spring.rabbitmq.listener.simple.auto-startup=true")` and assert it is still stopped without the marker. These
assertions must fail before the customizer is registered. The registry/container state is authoritative; do not assert
only the resolved property value.

- [ ] **Step 4: Add the capability enum and approved annotation**

Define:

```java
public enum TestBackgroundComponent {
    SCHEDULING,
    RABBIT_LISTENERS
}
```

and an inherited runtime type annotation whose `value()` is a `TestBackgroundComponent[]`. Meta-annotate it with:

```java
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
```

Do not give the annotation a default value; every use must state its capability.

- [ ] **Step 5: Implement the context customizer factory**

Use merged annotation lookup so inherited and composed annotations work. For default scheduling-off mode, register a
`BeanDefinitionRegistryPostProcessor`: its registry phase is a no-op and its later `postProcessBeanFactory` removes
`TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME` if present. This timing allows
`@EnableScheduling`'s configuration class to register the definition first, while still removing it before singleton
creation.

For default listener-off mode, add a named `MapPropertySource` at the front of the Environment containing:

```text
spring.rabbitmq.listener.simple.auto-startup=false
```

This property is acceptable because Step 2 proves it controls every current production listener factory. Narrowly
inspect Spring AMQP for a simpler stable registry/container-level enforcement hook. Use such a hook only if it avoids
factory replacement and preserves clean opt-in; do not add disproportionate lifecycle machinery. In all cases, Step
3's registry behavior is the acceptance criterion.

The customizer's equality/hash code must depend only on the two capability booleans. Do not key it by test class.

- [ ] **Step 6: Register or merge the factory**

Add the standard Spring factories entry:

```properties
org.springframework.test.context.ContextCustomizerFactory=\
com.example.relay.support.background.BackgroundExecutionContextCustomizerFactory
```

If the file already contains that key, append this implementation without removing or duplicating existing
implementations. Preserve all other keys and registrations.

- [ ] **Step 7: Add positive opt-in and cache-identity contracts**

Add focused contexts annotated for scheduling-only, listener-only, and both. Use `CountDownLatch` callbacks to prove
the selected real framework path starts; assert the other path remains inactive. For `RABBIT_LISTENERS`, assert the
expected production listener IDs are running through `RabbitListenerEndpointRegistry`, including `deliveryWorker`
from the custom factory. Construct customizers for all four modes and assert equal modes compare equal while different
modes do not.

- [ ] **Step 8: Run the policy tests**

Run:

```bash
./mvnw test -Dtest=BackgroundExecutionContextPolicyTest
```

Expected: all default-off, property-precedence, positive opt-in, cache-key, and context-close assertions pass with no
sleep-based coordination.

- [ ] **Step 9: Commit the policy boundary**

```bash
git add src/test/java/com/example/relay/support/background \
  src/test/resources/META-INF/spring.factories
git commit -m "test: disable autonomous background execution by default"
```

**Invariant established:** An ordinary Spring context has no autonomous scheduled callback and zero running production
Rabbit listener containers. A test cannot acquire either merely by loading the application or setting an enable
property.

**Regression evidence:** The contract fails without the factory and passes with it; real opt-in framework callbacks are
latched, not inferred.

---

### Task 2: Migrate the intentional scheduling tests

**Files:**

- Modify: `src/test/java/com/example/relay/deliveryengine/scheduling/ScheduledLoopEnabledSmokeTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/deadletter/DeadLetterNotifierRecoveryIntegrationTest.java`
- Modify: `src/test/java/com/example/relay/user/recovery/PasswordResetEmailRecoverySweeperIntegrationTest.java`

**Interfaces:**

- Consumes: `@EnableTestBackgroundExecution(SCHEDULING)` and combined scheduling/listener mode.
- Produces: the only full-context tests allowed to run production timers.

- [ ] **Step 1: Mark the minimal scheduling smoke test**

Annotate `ScheduledLoopEnabledSmokeTest` with scheduling opt-in. Keep its 25 ms cadence and Awaitility verification of
the real `@Scheduled` registration. Remove its explicit `@DirtiesContext` because the opt-in annotation now supplies
that lifecycle rule.

- [ ] **Step 2: Mark dead-letter scheduled recovery**

Annotate `DeadLetterNotifierRecoveryIntegrationTest` with both `SCHEDULING` and `RABBIT_LISTENERS`. Retain its 2s
reconciliation cadence and its end-to-end assertion that the second dead-letter delivery claims the row.

- [ ] **Step 3: Mark password-reset recovery for its actual end-to-end contract**

Use combined opt-in:

```java
@EnableTestBackgroundExecution({SCHEDULING, RABBIT_LISTENERS})
```

The test already provisions RabbitMQ, mocks `EmailService`, and increments a send counter, so its intended contract is
end-to-end scheduled recovery through `EmailDispatchConsumer`, not publication-only. In
`undispatchedToken_isRecovered_byIssuingAFreshTokenAndInvalidatingTheStaleOne`, await and assert all of:

- the stale token is invalidated and remains unclaimed for dispatch;
- exactly one successor token exists;
- the successor's `resetEmailDispatchedAt` is non-null;
- `sendCount` is exactly 1.

Remove the class's direct `@DirtiesContext` because the combined opt-in annotation supplies it.

Replace `aTokenPastExpiry_isNeverPickedUpBySweep`'s fixed `Thread.sleep(5000)` with event-driven coordination: spy or
latch the real sweeper invocation, wait until at least one scheduled sweep has completed, then assert the expired row
was untouched. Do not replace it with a shorter sleep.

- [ ] **Step 4: Verify direct-call scheduler tests remain background-free**

Run:

```bash
./mvnw test -Dtest=ScheduledLoopGatingTest,RetrySchedulerPostgresTest,ReadyWorkDispatcherIntegrationTest,ReadyWorkDispatcherRecoveryIntegrationTest,ReconciliationSweeperIntegrationTest,PasswordResetTokenCleanupTaskTest,PasswordResetEmailRecoverySweeperTest,ScheduledLoopEnabledSmokeTest,DeadLetterNotifierRecoveryIntegrationTest,PasswordResetEmailRecoverySweeperIntegrationTest
```

Expected: direct-call tests pass with no autonomous callbacks; the three marked tests prove their intended live loop.

- [ ] **Step 5: Commit scheduling opt-ins**

```bash
git add src/test/java/com/example/relay/deliveryengine/scheduling/ScheduledLoopEnabledSmokeTest.java \
  src/test/java/com/example/relay/deliveryengine/deadletter/DeadLetterNotifierRecoveryIntegrationTest.java \
  src/test/java/com/example/relay/user/recovery/PasswordResetEmailRecoverySweeperIntegrationTest.java
git commit -m "test: opt scheduler integration tests into live loops"
```

**Invariant established:** Real timer behavior remains tested, and every timer-enabled context closes after its class.

**Regression evidence:** Scheduling smoke latches fire only in marked contexts; direct-call suites remain stable.

---

### Task 3: Migrate intentional Rabbit consumers

**Files:**

- Modify: `src/test/java/com/example/relay/delivery/application/DeliveryReplayLifecycleIntegrationTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/deadletter/DeadLetterNotifierIntegrationTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerAckLifecycleTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerAggregateCapacityFourByTenTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerAggregateCapacityOneByFortyTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerConcurrencyTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerInFlightStateTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerIntegrationTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerScheduledIsolationIntegrationTest.java`
- Modify: `src/test/java/com/example/relay/email/EmailDispatchFailureLoggingIntegrationTest.java`
- Modify: `src/test/java/com/example/relay/email/EmailDispatchIntegrationTest.java`
- Modify: `src/test/java/com/example/relay/email/EmailVerificationDispatchFailureIntegrationTest.java`
- Modify: `src/test/java/com/example/relay/user/recovery/PasswordResetEmailRecoverySweeperIntegrationTest.java` with the combined mode decided in Task 2.

**Interfaces:**

- Consumes: `@EnableTestBackgroundExecution(RABBIT_LISTENERS)`.
- Produces: an explicit list of tests allowed to consume messages autonomously.

- [ ] **Step 1: Annotate worker-consumer tests**

Add listener opt-in to the replay and worker classes listed above. Where a class already has direct
`@DirtiesContext(AFTER_CLASS)`, remove only that redundant annotation/import. Keep manual stopping/starting of
unrelated listener IDs inside test fixtures; capability opt-in starts the production registry as today.

- [ ] **Step 2: Annotate dead-letter and email-consumer tests**

Add listener opt-in to `DeadLetterNotifierIntegrationTest` and the three email classes. Keep the combined opt-in on
`DeadLetterNotifierRecoveryIntegrationTest` from Task 2.

- [ ] **Step 3: Keep publisher/topology/manual dispatcher tests unmarked**

Run `RabbitMqConfigIntegrationTest`, `AttemptPublisherIntegrationTest`, both dispatcher integration tests, and
`ReconciliationSweeperIntegrationTest` without listener opt-in. Confirm queues/exchanges/publisher confirms and manual
queue reads still work while `RabbitListenerEndpointRegistry` containers report not running.

- [ ] **Step 4: Run the real-consumer selection**

Run:

```bash
./mvnw test -Dtest=DeliveryReplayLifecycleIntegrationTest,DeadLetterNotifierIntegrationTest,DeadLetterNotifierRecoveryIntegrationTest,DeliveryWorkerAckLifecycleTest,DeliveryWorkerAggregateCapacityFourByTenTest,DeliveryWorkerAggregateCapacityOneByFortyTest,DeliveryWorkerConcurrencyTest,DeliveryWorkerInFlightStateTest,DeliveryWorkerIntegrationTest,DeliveryWorkerScheduledIsolationIntegrationTest,EmailDispatchFailureLoggingIntegrationTest,EmailDispatchIntegrationTest,EmailVerificationDispatchFailureIntegrationTest
```

Expected: all existing listener, acknowledgment, capacity, recovery, and email assertions pass; context shutdown logs
show consumers and managed executors stopping after each opt-in class.

- [ ] **Step 5: Commit listener opt-ins**

```bash
git add src/test/java/com/example/relay/delivery \
  src/test/java/com/example/relay/deliveryengine \
  src/test/java/com/example/relay/email
git commit -m "test: opt Rabbit integration tests into live consumers"
```

**Invariant established:** Rabbit consumption is explicit and test-owned; publisher/topology tests have no competing
consumer.

**Regression evidence:** Existing real-consumer suites prove behavior while unmarked Rabbit suites prove stopped
containers do not prevent topology/publishing tests.

---

### Task 4: Add deterministic cross-context race regressions

**Files:**

- Create: `src/test/java/com/example/relay/support/background/BackgroundExecutionCrossContextRegressionTest.java`
- Reuse: `src/test/java/com/example/relay/support/SharedPostgresContainer.java`

**Interfaces:**

- Consumes: the customizer default mode, real `RetryScheduler`, real `ReadyWorkDispatcher`, real repositories, and the
  shared PostgreSQL container.
- Produces: `ControllableTaskScheduler.capture(Runnable)` and `runCapturedTasks()` test helpers scoped inside the test.

- [ ] **Step 1: Build the controllable scheduler fixture**

Implement a test scheduler that records scheduled callbacks without running them. `runCapturedTasks()` must take a
snapshot, execute each callback once on the calling test thread, and count down a completion latch. Do not use a
wall-clock delay.

- [ ] **Step 2: Write the policy-bypassed retry causal proof**

Construct the foreign source context deliberately outside the Spring TestContext customizer path. Wire the real
`RetryScheduler`, real `ReadyWorkRepositoryImpl`, production scheduling registration, and the capturing scheduler.
Use a separate victim context against `SharedPostgresContainer`, commit a database-due `SCHEDULED` child, release the
captured real retry callback, and assert the foreign production SQL changes it to `CREATED`.

The causal proof passes by observing the historical mutation; it does not synthesize an equivalent callback and does
not require editing/removing the factory registration during the test run.

- [ ] **Step 3: Write the policy-bypassed reset-then-claim causal proof**

Wire a policy-bypassed foreign context with the real `ReadyWorkDispatcher`, real `ReadyWorkRepositoryImpl`, production
scheduling registration, and capturing scheduler. In the victim context, create and commit a stale `IN_FLIGHT`
attempt, call the real reconciliation sweep, and verify the committed `CREATED` reset. Release the captured real
dispatcher callback and assert that production `claimUnpublishedReady` writes a foreign `ready_dispatch_claim_id` and
`ready_dispatch_claimed_at`.

- [ ] **Step 4: Prove ordinary P00 policy blocks both causal paths**

Repeat both victim-row setups with the source created as an ordinary Spring TestContext governed by P00. Assert the
capturing scheduler receives no production retry/dispatcher callback. Release its captured set (which must be empty)
and assert the retry child remains `SCHEDULED`, while the reset row retains null claim/publication fields. This is the
policy proof; Steps 2–3 remain the distinct historical causal proofs.

- [ ] **Step 5: Prove opt-in context closure with a latch**

Add a small application listener or destroy callback to an opt-in source context. After its test class lifecycle,
await the close latch and assert its scheduler and datasource are stopped before constructing the victim context. This
pins the annotation's `@DirtiesContext` consequence rather than relying on comments.

- [ ] **Step 6: Run the new regressions and historical reproducers**

Run:

```bash
./mvnw test -Dtest=BackgroundExecutionCrossContextRegressionTest
./mvnw test -Dtest=ReconciliationPropertiesTest,DeliveryWorkerIntegrationTest \
  -Drelay.retry.scheduler-interval=25ms
./mvnw test \
  '-Dtest=ReconciliationPropertiesTest,ReconciliationSweeperIntegrationTest#staleInFlightAttempt_isResetWithoutDirectPublication' \
  -Drelay.retry.dispatcher-interval=25ms
```

Expected: policy-bypassed harness cases deterministically observe mutations by the real production callbacks;
ordinary-policy cases observe no captured callbacks and no mutations; the retry ordered run has no
`SCHEDULED → CREATED` errors; the reconciliation ordered run leaves `ready_dispatch_claim_id` null.

- [ ] **Step 7: Commit race regressions**

```bash
git add src/test/java/com/example/relay/support/background/BackgroundExecutionCrossContextRegressionTest.java
git commit -m "test: lock down cross-context scheduler isolation"
```

**Invariant established:** A cached ordinary context cannot promote or claim rows owned by a later context.

**Regression evidence:** Policy-bypassed cases use the real production components and SQL to reproduce both historical
foreign mutations; ordinary P00-governed cases acquire no callback and preserve victim state. The historical ordered
reproducers also turn green.

---

### Task 5: Remove redundant per-test disable lists and guard the inventory

**Files:**

- Modify: `src/test/java/com/example/relay/deliveryengine/reconciliation/ReconciliationSweeperIntegrationTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/dispatcher/ReadyWorkDispatcherIntegrationTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/dispatcher/ReadyWorkDispatcherRecoveryIntegrationTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerIntegrationTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerScheduledIsolationIntegrationTest.java`
- Modify other tests only where a disable property is now provably redundant.
- Extend: `src/test/java/com/example/relay/support/background/BackgroundExecutionContextPolicyTest.java`

**Interfaces:**

- Consumes: central default-off policy.
- Produces: one authoritative isolation mechanism instead of distributed property collections.

- [ ] **Step 1: Remove only background-activation disable properties**

Remove redundant `spring.task.scheduling.enabled=false`,
`spring.rabbitmq.listener.simple.auto-startup=false`, `relay.retry.scheduling-enabled=false`, and
`relay.reconciliation.scheduling-enabled=false` from ordinary tests. Preserve cadence, grace, batch-size, confirm, and
other properties that affect direct method behavior or validation.

- [ ] **Step 2: Add an inventory guard**

Extend the policy test to scan application beans/methods annotated with `@Scheduled` and `@RabbitListener`. Assert
that an ordinary full context has zero registered scheduled tasks. For every discovered `@RabbitListener`, resolve its
listener ID and container factory, then assert the corresponding `RabbitListenerEndpointRegistry` container exists but
is not running. Include diagnostic output listing component, method, listener ID, and factory so a future listener or
factory that escapes the current property fails visibly. In listener opt-in mode, assert the intended production IDs
are running.

- [ ] **Step 3: Run focused ordinary contexts**

Run:

```bash
./mvnw test -Dtest=RelayApplicationTests,ReconciliationPropertiesTest,DeliveryListenerPropertiesTest,DeliveryListenerPropertiesConfigurationKeysTest,PasswordResetTokenCleanupTaskTest,ReconciliationSweeperIntegrationTest,ReadyWorkDispatcherIntegrationTest,ReadyWorkDispatcherRecoveryIntegrationTest
```

Expected: all pass; no `relay-retry-*` database callbacks and no Rabbit reconnect consumers are active in unmarked
contexts.

- [ ] **Step 4: Commit cleanup and guard**

```bash
git add src/test/java/com/example/relay/support/background/BackgroundExecutionContextPolicyTest.java \
  src/test/java/com/example/relay/deliveryengine
git commit -m "test: centralize background isolation policy"
```

**Invariant established:** Ordinary test authors do not maintain a growing disable-property checklist.

**Regression evidence:** Full-context inventory proves every current and future annotated component is inert by
default.

---

### Task 6: Full verification and durable documentation

**Files:**

- Modify: `docs/reviews/probes/2026-09-30-p03-full-suite-lifecycle-investigation.md`
- Inspect: `target/surefire-reports/TEST-*.xml`, thread/log output, `git diff`

**Interfaces:**

- Consumes: all prior tasks.
- Produces: final focused, integration-tier, and full-suite evidence plus the P03 investigation resolution link.

- [ ] **Step 1: Run formatting and focused isolation tests**

```bash
./mvnw spotless:check
./mvnw test -Dtest=BackgroundExecutionContextPolicyTest,BackgroundExecutionCrossContextRegressionTest,ScheduledLoopEnabledSmokeTest
```

Expected: formatting passes; all policy, race, and real-scheduling smoke tests pass.

- [ ] **Step 2: Run the integration tier**

```bash
make test-integration-docker
```

Expected: zero failures/errors/skips. Inspect logs to confirm ordinary contexts do not start Rabbit consumers or emit
periodic database scheduler SQL; marked contexts do.

- [ ] **Step 3: Run the full suite**

```bash
make test-full-docker
```

Expected: zero failures/errors/skips, including the unchanged strict lifecycle assertions.

- [ ] **Step 4: Repeat the two former failure sequences**

Run each historical reproducer three times. Expected on every run: no promotion errors and no foreign claim. Record
counts and commands in the P03 investigation appendix; do not claim determinism from a single green run.

- [ ] **Step 5: Audit production scope and thread ownership**

```bash
git diff --name-only -- src/main
rg -n '@Scheduled|@RabbitListener|ApplicationRunner|ExecutorService|TaskScheduler' src/main/java
git diff --check
git status --short
```

Expected: no `src/main` diff; the inventory matches the design; no whitespace errors; unrelated work is preserved.

- [ ] **Step 6: Update the investigation record**

Append implementation revision, focused/full counts, three-run ordered reproducer evidence, and links to the P00
design/plan. Do not rewrite the historical P03 conclusion.

- [ ] **Step 7: Commit final evidence**

```bash
git add docs/reviews/probes/2026-09-30-p03-full-suite-lifecycle-investigation.md
git commit -m "docs: record deterministic background test isolation"
```

**Invariant established:** P00 is proven by focused causal regressions and the uncapped full gate, with no production
change.

**Regression evidence:** Three consecutive green runs of both former ordered failures, plus green integration and full
suites.
