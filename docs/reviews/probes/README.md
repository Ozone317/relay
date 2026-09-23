# Second-pass readiness probes

Companion to [Readiness Verification & Engineering Projects](../2026-09-23-readiness-verification-and-projects.md).

`ReadinessProbe.java` is a standalone review harness, outside application sources and the default test suite. Its P01 transport assertions verify the corrected UTF-8 JSON/signature invariant, while later assertions intentionally characterize current defects. A successful run confirms the P01 invariant and reproduces the remaining characterized defects; it is **not a claim that Relay is safe or that all fixes passed**. Keep invariant-preserving checks in the appropriate project regression tests; do not add this harness unchanged to CI. The dated readiness reports preserve their pre-implementation findings and current-at-review evidence; this harness now mixes the repaired P01 invariant with remaining defect characterizations.

## Scope and safety

Requires Java 21, Maven dependencies, and a local Docker engine capable of running PostgreSQL 16. Uses an ephemeral Testcontainers database with the repository's Flyway migrations, synthetic accounts, local HTTP receivers, and mocked email/dead-letter publishers. It does not use a production database, send real email, or contact customer destinations. The minimal Spring context has no component scanning or scheduled-job activation; tested jobs are invoked explicitly. The database container is removed when the run ends.

The harness does write synthetic data to its isolated database and compile classes under `target/readiness-review`. It does not modify production source. The `productionFilesModified=false` output is a descriptive label, not a filesystem assertion; repository changes were separately checked with Git.

Concurrency uses latches and committed operations. The stale-worker case simulates an expired claim through timestamps, rather than inducing a real 90-second pause. The replay gate surrounds the real service's creation call. The reset gate wraps the real candidate query, then runs real token transactions. Email dispatch confirmation is written through the production repository method and checked before recovery resumes; actual provider delivery is not tested.

## Reproduce

Run from `/home/daksh/Personal/relay`. First compile and run the selected existing regressions; this also creates a fresh Surefire classpath record:

```bash
./mvnw -q \
  -Dtest=HmacSignerTest,DeliveryWorkerAckLifecycleTest,DeliveryHttpClientPinningTest,DeliveryHttpClientConnectTimeoutTest,AttemptServiceMarkFailedAndCreateRetryAtomicityTest,DeliveryReplayConcurrencyPostgresTest,DeliveryReplayLifecycleIntegrationTest,PasswordResetEmailRecoverySweeperTest,PasswordResetConcurrentRequestPostgresTest,ScheduledLoopGatingTest \
  -Drelay.retry.scheduling-enabled=false test
```

Then compile/run the standalone harness. Node is used only to extract the resolved local test classpath, not to generate or change application code:

```bash
relay_review_cp=$(node -e '
  const fs = require("fs");
  const xml = fs.readFileSync(
    "target/surefire-reports/TEST-com.example.relay.attempt.application.AttemptServiceMarkFailedAndCreateRetryAtomicityTest.xml", "utf8");
  console.log(xml.match(/name="java.class.path" value="([^"]+)"/)[1].replaceAll("&amp;", "&"));
')
mkdir -p target/readiness-review
javac -proc:none -cp "$relay_review_cp" \
  -d target/readiness-review docs/reviews/probes/ReadinessProbe.java
java -Dspring.devtools.restart.enabled=false -Ddebug=false \
  -Dlogging.level.org.springframework=ERROR \
  -cp "target/readiness-review:$relay_review_cp" ReadinessProbe
```

Disabling DevTools restart matters for a standalone main on the test classpath. The first exploratory invocation allowed restart and exited nonzero despite reaching the final marker. Two subsequent invocations with restart disabled exited zero, including the final version with the explicit reset-confirmation assertion. Only the clean runs are counted as successful evidence.

For concise output, pipe the `java` command through `awk '/PROBE |Exception in thread|Caused by:|AssertionError|APPLICATION FAILED/ {print}'` with Bash `set -o pipefail`; retain the Java exit status. Expected PostgreSQL length violations occur deliberately inside the probe.

## Observed final output

```text
PROBE unicode contentType=application/json utf8Matches=true receiverSignatureMatches=true
PROBE ssrf loopbackDtoAccepted=true actualWorkerReachedLoopback=true
PROBE ascii-control contentType=application/json utf8Matches=true receiverSignatureMatches=true
PROBE large-success http200Calls=2 status=IN_FLIGHT sameAttemptNo=1
PROBE large-failure-control status=FAILED_RETRYING capturedCharacters=10240
PROBE redirect-control followed=false recordedStatus=302
PROBE stale-worker B_SUCCEEDED_overwritten_by_A_FAILED_RETRYING=true scheduledChild=1
PROBE stale-replay attemptNo7Rows=2 newReplayAfterSuccess=true
PROBE stale-reset-recovery freshConfirmedTokenInvalidated=true singletonSweeperPlusUserRequest=true
PROBE scheduler unqualifiedThread=relay-retry-1 qualifiedTickBlockedUntilRelease=true
PROBE COMPLETE allExpectedOutcomesObserved=true productionFilesModified=false
```

## Existing regressions run in this pass

Maven exited zero. Fresh Surefire reports contained **23 tests, zero failures, zero errors, zero skipped**, across these ten classes:

| Class | Tests |
|---|---:|
| HmacSignerTest | 4 |
| DeliveryWorkerAckLifecycleTest | 2 |
| DeliveryHttpClientPinningTest | 1 |
| DeliveryHttpClientConnectTimeoutTest | 2 |
| AttemptServiceMarkFailedAndCreateRetryAtomicityTest | 1 |
| DeliveryReplayConcurrencyPostgresTest | 1 |
| DeliveryReplayLifecycleIntegrationTest | 3 |
| PasswordResetEmailRecoverySweeperTest | 3 |
| PasswordResetConcurrentRequestPostgresTest | 1 |
| ScheduledLoopGatingTest | 5 |

These passing controls verify existing protections and expose gaps in their interleaving/receiver coverage. They do not disprove the independently reproduced defects. The full suite, production deployment, actual egress policy, heap-exhaustion behavior, and real load/restore envelope were **not** validated in this pass. The first report's separate 62-test selection is not counted again here. No blanket test parallelism or broker-default changes were introduced.
