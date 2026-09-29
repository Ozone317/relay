# Second-pass readiness probes

Companion to [Readiness Verification & Engineering Projects](../2026-09-23-readiness-verification-and-projects.md).

`ReadinessProbe.java` is a standalone review harness, outside application sources and the default test suite. This harness and its output below are a **pre-P03 historical snapshot**: its SSRF result records the vulnerability before P03, and Task H removed the JDK delivery client API the harness constructs. It is no longer a current or runnable verification command. Keep invariant-preserving checks in the project regression tests; do not add this harness unchanged to CI. The dated readiness reports preserve their pre-implementation findings and evidence as of their review dates.

## Scope and safety

Requires Java 21, Maven dependencies, and a local Docker engine capable of running PostgreSQL 16. Uses an ephemeral Testcontainers database with the repository's Flyway migrations, synthetic accounts, local HTTP receivers, and mocked email/dead-letter publishers. It does not use a production database, send real email, or contact customer destinations. The minimal Spring context has no component scanning or scheduled-job activation; tested jobs are invoked explicitly. The database container is removed when the run ends.

The harness does write synthetic data to its isolated database and compile classes under `target/readiness-review`. It does not modify production source. The `productionFilesModified=false` output is a descriptive label, not a filesystem assertion; repository changes were separately checked with Git.

Concurrency uses latches and committed operations. The stale-worker case simulates an expired claim through timestamps, rather than inducing a real 90-second pause. The replay gate surrounds the real service's creation call. The reset gate wraps the real candidate query, then runs real token transactions. Email dispatch confirmation is written through the production repository method and checked before recovery resumes; actual provider delivery is not tested.

## Historical pre-P03 reproduction

The commands and outcomes in this section document how the pre-P03 harness was originally run. The standalone harness no longer compiles against the Task H production transport, so use the current Task J verification in `.superpowers/sdd/2026-09-24-p03-outbound-destination-safety/task-J-report.md` for P03 evidence.

The historical regression selection was superseded by this current Apache transport selection, which retains the P01/P02 and lifecycle controls while exercising the production P03 transport:

```bash
./mvnw test -Dtest=HmacSignerTest,DeliveryWorkerAckLifecycleTest,DeliveryHttpClientSecurityConfigTest,ApacheDnsSocketBindingIntegrationTest,ApacheWebhookHttpTransportTest,ApacheWebhookHttpTransportDeadlineTest,BoundedApacheResponseBodyConsumerTest,ApacheResponseConsumptionIntegrationTest,AttemptServiceMarkFailedAndCreateRetryAtomicityTest,DeliveryReplayConcurrencyPostgresTest,DeliveryReplayLifecycleIntegrationTest,PasswordResetEmailRecoverySweeperTest,PasswordResetConcurrentRequestPostgresTest,ScheduledLoopGatingTest
```

Task J ran this selection on 2026-09-29: **87 tests, zero failures, zero errors, zero skipped**.

The old standalone Java invocation is omitted because `ReadinessProbe.java` still constructs the removed JDK delivery client and cannot compile against the post-Task-H code.

## Observed pre-P03 output (historical)

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

## Existing regressions run in the historical pass

Maven exited zero. Fresh Surefire reports from the historical pre-P03 pass contained **23 tests, zero failures, zero errors, zero skipped**, across these ten classes:

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
