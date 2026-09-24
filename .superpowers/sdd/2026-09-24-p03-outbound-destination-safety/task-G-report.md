# Task G report — adversarial network and TLS proof

## Scope and RED evidence

Task G only was implemented from base `4ce5a23`. No production files, `DeliveryWorker`, or Tasks H–J were changed.

The first focused run was intentionally recorded before tightening the fixture expectations:

```text
./mvnw -q -Dtest=WebhookDestinationAdversarialIntegrationTest test
Tests run: 11, Failures: 3, Errors: 1
```

The failures were test-harness RED evidence: Apache exposed the expected
`DestinationPolicyBlockedException` directly for protected literal/DNS/mixed/replacement paths, and capturing an
unset Java proxy property with `Collectors.toMap` raised an NPE. No socket was reached and no resolver-bypass path was
observed. The fixture was corrected to assert the stable policy exception and preserve nullable system properties.

## GREEN evidence

Focused command, run twice (including the timing-sensitive network/TLS suite):

```text
./mvnw -q -Dtest=WebhookDestinationAdversarialIntegrationTest,WebhookTlsIdentityIntegrationTest,ApacheDnsSocketBindingIntegrationTest test
Each run: 18 tests, 0 failures, 0 errors (11 destination, 2 TLS, 5 existing binding)
```

P01/P02/deadline regressions:

```text
./mvnw -q -Dtest=ApacheWebhookHttpTransportTest,BoundedResponseBodyCaptureTest,BoundedApacheResponseBodyConsumerTest,ApacheResponseConsumptionIntegrationTest,ApacheWebhookHttpTransportDeadlineTest,DeliveryDeadlineContextTest,HmacSignerTest test
45 tests, 0 failures, 0 errors
```

The broader `./mvnw -q test` was started as a bounded verification run. Existing Spring/Testcontainers integration
tests emitted repeated `Connection refused` attempts to `localhost:1` from Rabbit listeners and scheduled loops; the
run was stopped after about three minutes rather than allowing those background retry loops to run indefinitely. The
reports written before the stop (53 class reports) contain no recorded failures. This is not claimed as a complete
full-suite pass; the focused and regression commands above are the authoritative green evidence.

## Network-boundary evidence

- Protected literal `127.0.0.1` and protected DNS answer opened zero listener sockets. The DNS answer was looked up
  once as the absolute name `rebind.test.`.
- Mixed answer `[127.0.0.2, 127.0.0.1]` was rejected before either listener accepted a connection.
- Failover answer `[127.0.0.3, 127.0.0.2]` reached only the listener bound to `127.0.0.2`; the first unavailable
  permitted candidate was tried, and the protected `127.0.0.1` trap was never in the returned set or contacted.
- Rebinding answer sequence `[127.0.0.2]`, then hypothetical `[127.0.0.1]`, performed exactly one lookup and one
  accepted request. The accepted listener recorded its local peer as `127.0.0.2`; the trap recorded zero accepts.
- Pool reuse served two requests on one `127.0.0.2` socket with one lookup. After the pool's closed idle connection was
  evicted, replacement performed a second lookup, rejected the protected result, and opened zero trap sockets.
- HTTP, HTTPS, SOCKS, and `java.net.useSystemProxies=true` traps were installed. The direct `127.0.0.2` listener
  received the request and the proxy listener received zero connections.
- 301, 302, 303, 307, and 308 responses each produced one destination request and zero trap connections.

The production policy remains independently strict; loopback is allowed only by the test catalog fixture where needed
to bind deterministic loopback listeners. No new-socket resolver bypass was demonstrated.

## TLS and identity evidence

The committed test-only CA signs both test certificates. `p03-webhook-test-cert.pem` has SAN `webhook.test`; the
wrong-host certificate has SAN `wrong.test`. The valid handshake succeeded, the server observed SNI `webhook.test`,
and the HTTP authority was `webhook.test:<port>`. A CA-trusted wrong-host certificate failed normal hostname
verification and the server observed zero HTTP request bytes. No trust-all context, permissive hostname verifier, or
disabled endpoint identification is used.

## Files and deviations

Added only the Task G fixture/tests/resources:

- `src/test/java/com/example/relay/deliveryengine/http/ControllableHostAddressLookup.java`
- `src/test/java/com/example/relay/deliveryengine/http/WebhookDestinationAdversarialIntegrationTest.java`
- `src/test/java/com/example/relay/deliveryengine/http/WebhookTlsIdentityIntegrationTest.java`
- `src/test/resources/tls/p03-test-ca.pem`
- `src/test/resources/tls/p03-webhook-test-cert.pem`
- `src/test/resources/tls/p03-webhook-test-key.pem`
- `src/test/resources/tls/p03-wrong-host-cert.pem`
- `src/test/resources/tls/p03-wrong-host-key.pem`

No production change or deviation from the Task G scope was required. `git diff --cached --check` is clean.
