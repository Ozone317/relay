# Task C report: bounded resolver boundary

## RED evidence

Added the specified Task C tests before production classes. The first focused run failed at test compilation because
`DeliveryDeadline`, `DeliveryDeadlineContext`, `DeliveryDnsProperties`, and the resolver/lookup classes did not yet
exist. This was the expected feature-missing RED state.

## GREEN evidence

Focused verification passed:

```text
./mvnw test -Dtest=DeliveryDnsPropertiesBindingTest,DeliveryDnsPropertiesValidationTest,
  DeliveryDnsPropertiesConfigurationKeysTest,DeliveryDeadlineContextTest,
  SystemHostAddressLookupTest,PolicyEnforcingDnsResolverTest
Tests run: 30, Failures: 0, Errors: 0, Skipped: 0
```

The compatibility listener-key test was included after excluding the new nested DNS namespace from its listener-only
field assertion. The combined focused run passed 31 tests.

Dependency verification:

```text
org.apache.httpcomponents.client5:httpclient5:5.5.2
org.apache.httpcomponents.core5:httpcore5:5.3.6
```

Coverage includes defaults/overrides, startup validation, canonical configuration keys, monotonic deadline behavior,
nested/missing/exceptional scope cleanup, absolute terminating-dot lookup, literal lookup bypass, mixed and protected
answer rejection, null/empty/unresolved fail-closed behavior, deadline expiry before lookup, timeout cancellation and
late completion, interruption flag restoration, bounded executor saturation/recovery, and configured capacity.

## Implementation

- Added Boot-managed `httpclient5` dependency with no explicit version.
- Added `DeliveryDeadline` and resolver-only `DeliveryDeadlineContext` in the planned `http` package.
- Added injectable `HostAddressLookup` and bounded `SystemHostAddressLookup` around platform
  `InetAddress.getAllByName`, including absolute names, min(DNS timeout, remaining deadline), cancellation, and abort
  policy saturation handling.
- Added validated node-level `DeliveryDnsProperties` and daemon bounded executor configuration.
- Added `PolicyEnforcingDnsResolver` overriding `resolve(String, int)` and rejecting the unsupported one-argument path.
  It returns only classified concrete socket addresses and rejects complete mixed result sets.
- Added canonical `relay.delivery.dns.max-concurrency`, `relay.delivery.dns.queue-capacity`, and
  `relay.delivery.dns.timeout` defaults to `application.properties`.
- Reused Task A's `IpLiteral.parse(String)` and Task B's existing `PublicDestinationAddressPolicy` and
  `DestinationPolicyBlockedException`; no duplicate policy exception, dnsjava, or Relay cache was added.

## Full suite

The required `./mvnw test` run was started once after the focused suite. It reached the repository's existing
integration/scheduler churn and recorded these unrelated P00 timing/isolation failures:

- `ReconciliationSweeperIntegrationTest.staleInFlightAttempt_isResetWithoutDirectPublication`: Awaitility timeout;
  `ready_dispatch_claim_id` remained set.
- `DeliveryWorkerIntegrationTest.non2xxResponse_onAttemptBeforeFinal_createsFinalRetry`: Awaitility timeout;
  retry remained `CREATED` rather than `SCHEDULED`.

The run then remained in repeated Rabbit listener connection-refused churn with no new test summary, so it was
terminated after the failures were captured. The Task C focused and compatibility suites remained green.

## Self-review and concerns

- No bare literal enters `WebhookUriParser`; literal bytes come from `IpLiteral.parse`.
- No resolver path returns `null`, an empty fallback, an unresolved socket, or an unclassified candidate.
- The system lookup has fixed worker count and an `ArrayBlockingQueue`; rejection fails closed.
- A timed-out future is cancelled, and a late platform result has no path back to Apache.
- The deadline context is a scoped `ThreadLocal` adapter only; it is not used by unrelated code.
- Full-suite completion is blocked by the pre-existing scheduler/listener integration behavior described above; no Task C
  code or tests were weakened to mask it.
