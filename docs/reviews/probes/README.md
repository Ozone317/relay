# Second-pass readiness probes

Companion to [Readiness Verification & Engineering Projects](../2026-09-23-readiness-verification-and-projects.md).

`ReadinessProbe.java` is a standalone review harness, outside application sources and the default test suite. This harness and its output below are a **pre-P03 historical snapshot**: its SSRF result records the vulnerability before P03, and Task H removed the JDK delivery client API the harness constructs. It is no longer a current or runnable verification command. Keep invariant-preserving checks in the project regression tests; do not add this harness unchanged to CI. The dated readiness reports preserve their pre-implementation findings and evidence as of their review dates.

## Scope and safety

Requires Java 21, Maven dependencies, and a local Docker engine capable of running PostgreSQL 16. Uses an ephemeral Testcontainers database with the repository's Flyway migrations, synthetic accounts, local HTTP receivers, and mocked email/dead-letter publishers. It does not use a production database, send real email, or contact customer destinations. The minimal Spring context has no component scanning or scheduled-job activation; tested jobs are invoked explicitly. The database container is removed when the run ends.

The harness does write synthetic data to its isolated database and compile classes under `target/readiness-review`. It does not modify production source. The `productionFilesModified=false` output is a descriptive label, not a filesystem assertion; repository changes were separately checked with Git.

Concurrency uses latches and committed operations. The stale-worker case simulates an expired claim through timestamps, rather than inducing a real 90-second pause. The replay gate surrounds the real service's creation call. The reset gate wraps the real candidate query, then runs real token transactions. Email dispatch confirmation is written through the production repository method and checked before recovery resumes; actual provider delivery is not tested.

## Current post-P03 verification

Run date: 2026-09-29. P03 focused checks and the current Apache transport regressions passed. The one full-suite run
reported four lifecycle timing errors. The subsequent bounded causal investigation is preserved in
[P03 full-suite lifecycle-error investigation](2026-09-30-p03-full-suite-lifecycle-investigation.md).

Focused P03 command and result after the interruption-test correction: **140 tests, 0 failures, 0 errors, 0 skipped**.
The exact interruption test also passed six consecutive runs.

```bash
./mvnw test -Dtest=WebhookUriParserTest,WebhookUrlValidatorTest,PublicDestinationAddressPolicyTest,SpecialPurposeAddressCatalogTest,DeliveryDnsPropertiesBindingTest,DeliveryDnsPropertiesValidationTest,DeliveryDnsPropertiesConfigurationKeysTest,DeliveryDeadlineContextTest,SystemHostAddressLookupTest,PolicyEnforcingDnsResolverTest,DeliveryHttpClientSecurityConfigTest,ApacheDnsSocketBindingIntegrationTest,ApacheWebhookHttpTransportTest,ApacheWebhookHttpTransportDeadlineTest,BoundedApacheResponseBodyConsumerTest,ApacheResponseConsumptionIntegrationTest,WebhookDestinationAdversarialIntegrationTest,WebhookTlsIdentityIntegrationTest
```

The adapted post-P01/P02 lifecycle command and result: **87 tests, 0 failures, 0 errors, 0 skipped**. The two JDK
transport tests in the earlier probe command were removed with that transport in Task H; the Apache security, binding,
deadline, and response suites below cover the active production transport.

```bash
./mvnw test -Dtest=HmacSignerTest,DeliveryWorkerAckLifecycleTest,DeliveryHttpClientSecurityConfigTest,ApacheDnsSocketBindingIntegrationTest,ApacheWebhookHttpTransportTest,ApacheWebhookHttpTransportDeadlineTest,BoundedApacheResponseBodyConsumerTest,ApacheResponseConsumptionIntegrationTest,AttemptServiceMarkFailedAndCreateRetryAtomicityTest,DeliveryReplayConcurrencyPostgresTest,DeliveryReplayLifecycleIntegrationTest,PasswordResetEmailRecoverySweeperTest,PasswordResetConcurrentRequestPostgresTest,ScheduledLoopGatingTest
```

The single full-suite command was `./mvnw test`: **768 tests, 0 assertion failures, 4 errors, 0 skipped**. The errors
were:

- `ReconciliationSweeperIntegrationTest.staleInFlightAttempt_isResetWithoutDirectPublication`: the reset row was
  expected to have no ready-dispatch lease, but `ready_dispatch_claim_id` was populated during the assertion window.
- `DeliveryWorkerIntegrationTest.exception_beforeFinalAttempt_marksAttemptFailedAndCreatesRetry`: the retry child was
  expected to remain `SCHEDULED`, but was observed as `CREATED`.
- `DeliveryWorkerIntegrationTest.protectedDestination_isRejectedThroughWorkerLifecycleWithoutOpeningListener`:
  the protected-DNS and mixed-DNS cases each expected a `SCHEDULED` retry child but observed `CREATED`.

The causal follow-up identified the exact writers as background scheduler/dispatcher components in other cached Spring
contexts sharing the same PostgreSQL database. Both transition families reproduced through the same mechanism on
pre-P03 revision `9044e47`. The existing exception-path error is a pre-existing test-isolation race; the two P03-only
DNS cases are timing exposure of that same race rather than P03 transport regressions. No P03 policy, transport, P01,
or P02 assertion failed, and no P03 production change is required. The separately tracked P00 isolation issue can
still make the uncapped full suite nondeterministic.

The forbidden-configuration audit used:

```bash
rg -n "allow-private|NoopHostnameVerifier|TrustAll|useSystemProperties|setProxy\(|setProxySelector|SocksProxy|followRedirect|disableHostnameVerification|dnsjava" src pom.xml
```

Matches were explicit no-SOCKS configuration, negative proxy/SOCKS test assertions, and comments documenting forbidden
system-property behavior. No private-address bypass, permissive TLS, proxy route, redirect following, or dnsjava
dependency was found. The branch scope audit found no schema/migration, Attempt-state, retry-policy, URL-snapshot, or
scheduler production changes. See the tracked P03 security invariants in
[`docs/superpowers/specs/2026-09-24-p03-outbound-destination-safety-design.md`](../../superpowers/specs/2026-09-24-p03-outbound-destination-safety-design.md#19-security-invariants-checklist).

## Historical pre-P03 reproduction

The following output records pre-P03 behavior only. `ReadinessProbe.java` depends on the JDK delivery client removed
in Task H and is no longer a current or runnable verification harness.

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
