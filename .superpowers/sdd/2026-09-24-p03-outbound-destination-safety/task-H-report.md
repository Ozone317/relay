# Task H report — integrate WebhookHttpTransport into DeliveryWorker

Base: `ac669dc`
Scope: Task H only

## RED evidence

Worker fixtures were updated first to inject `WebhookHttpTransport`, exercise modeled stable failure codes, and assert
unexpected runtime exceptions remain exceptional. Before the production switch, the focused run failed because the
worker still used the old `RestClient` path; the first fixture run also exposed the checked-exception declarations
needed by the fake transport. No production switch preceded this RED observation.

## GREEN implementation

- `DeliveryWorker` now receives only `WebhookHttpTransport`.
- It still creates one UTF-8 `byte[]`, signs it, and passes the same reference to `post(endpoint.url, body, headers)`.
- It catches only `WebhookDeliveryException`; persisted diagnostics begin with `failureCode + ": "` and use the
  exception's bounded diagnostic. Runtime/programming exceptions remain uncaught and complete the worker future
  exceptionally.
- 2xx and non-2xx response handling, attempt identity/numbering, retry timing and jitter, atomic retry creation,
  dead-letter publication, and acknowledgment ownership are unchanged.
- `DeliveryHttpClientConfig` now exposes the production `ApacheWebhookHttpTransport` bean over the existing verified
  Apache client, resolver, deadline, TLS, redirect, retry, and bounded-consumer construction.

## Lifecycle evidence

`DeliveryWorkerAckLifecycleTest` has 13 passing tests, including all five modeled codes at attempt 1 and attempt 6:

- attempts 1–5 path: `FAILED_RETRYING` plus one `SCHEDULED` child;
- attempt 6 path: `DEAD` with no child;
- stable bounded `last_error` prefix;
- modeled failure acknowledgment after persistence;
- no modeled failure leaves `IN_FLIGHT`.

`DeliveryWorkerInFlightStateTest` has 2 passing tests. Its runtime-boundary case proves an unexpected
`IllegalStateException` is not normalized/persisted and the worker future completes exceptionally while the claimed row
remains `IN_FLIGHT`.

## Exact bytes and response behavior

The four parameterized UTF-8/control/Unicode worker fixtures pass with exact received body bytes and recomputed HMAC
signatures. The selected success and non-2xx integration checks pass. The worker fixture uses a test-only direct client
to reach its loopback MockWebServer; production still uses only the Apache transport.

## Removed legacy path

Removed after usage audit:

- `deliveryRestClient` bean and JDK `buildHttpClient` construction/imports;
- delivery-specific `DeliveryHttpClientPinningTest`;
- delivery-specific `DeliveryHttpClientConnectTimeoutTest`;
- unused `BoundedResponseBodyCapture.captureAndClose` wrapper.

The unrelated Brevo `RestClient` configuration and sender remain untouched.

## Verification

Passing focused evidence:

- `DeliveryWorkerAckLifecycleTest,DeliveryWorkerInFlightStateTest,AttemptServiceMarkFailedAndCreateRetryAtomicityTest`:
  16 tests, 0 failures/errors.
- `ApacheWebhookHttpTransportTest,ApacheWebhookHttpTransportDeadlineTest,BoundedApacheResponseBodyConsumerTest,DeliveryHttpClientSecurityConfigTest`:
  25 tests, 0 failures/errors.
- Selected `DeliveryWorkerIntegrationTest` success, exact-byte parameterized (4), and non-2xx retry cases:
  6 tests, 0 failures/errors.
- `./mvnw -q -DskipTests compile` and test compilation: pass.
- `git diff --check`: pass.

The complete `DeliveryWorkerIntegrationTest` class was attempted as a bounded broader run but not completed; its
malformed-UUID/requeue test produced the existing repeated listener activity and exceeded the bounded observation
window. It is not claimed green here. No full repository suite was run in this Task H turn.

## Files changed

- `src/main/java/com/example/relay/deliveryengine/worker/DeliveryWorker.java`
- `src/main/java/com/example/relay/deliveryengine/config/DeliveryHttpClientConfig.java`
- `src/main/java/com/example/relay/deliveryengine/worker/BoundedResponseBodyCapture.java`
- `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerAckLifecycleTest.java`
- `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerInFlightStateTest.java`
- `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerIntegrationTest.java`
- deleted `src/test/java/com/example/relay/deliveryengine/config/DeliveryHttpClientPinningTest.java`
- deleted `src/test/java/com/example/relay/deliveryengine/config/DeliveryHttpClientConnectTimeoutTest.java`

Deviations are limited to the bounded full-integration limitation above; no Task I/J, lifecycle, retry, replay,
schema, or Brevo changes were made.

Implementation commit: `9f4aa8b` (`feat: integrate webhook transport into delivery worker`).
