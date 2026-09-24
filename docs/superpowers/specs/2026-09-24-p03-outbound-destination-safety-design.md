# Design: P03 Outbound Destination Safety / SSRF Protection

**Status:** Approved for implementation planning. Production implementation has not started.

## 1. Problem and reproduced vulnerability

Relay accepts customer-controlled webhook endpoint URLs and delivers messages to them asynchronously. The current
endpoint DTO validation checks URL shape and an `http`/`https` regex, but it does not enforce a public-destination
network policy.

The P03 readiness probe reproduced the vulnerability against the post-P02 repository:

- endpoint validation accepted a loopback URL;
- the endpoint passed through the application boundary;
- the real `DeliveryWorker`, using the configured delivery HTTP client, connected to the loopback listener; and
- the listener received the webhook request.

This is an exploitable outbound connection path, not only a validation-theory finding.

Endpoint URLs are mutable. `Delivery` and `Attempt` persist an endpoint relationship, not a URL snapshot. Initial
attempts, automatic retries, and manual replays load the current `Endpoint.url` when the worker executes. A URL or
DNS answer that was harmless when an endpoint was created can therefore be different when any later attempt runs.

P03 must enforce destination policy during every delivery execution and, critically, whenever a new outbound socket
is established.

## 2. Security invariant

> **For ordinary customer webhook endpoints, every newly established outbound webhook socket must connect only to an
> address that has passed Relay's destination policy for that connection.**

The following are not sufficient security boundaries:

- URL string or regex validation;
- endpoint-creation DNS resolution;
- a DNS answer retained indefinitely from CRUD time;
- resolving and checking a hostname, then giving the original hostname to an HTTP client that resolves again;
- checking only one address when the transport may fail over to other addresses; or
- infrastructure assumptions that are not enforced by Relay or evidenced by deployment configuration.

Endpoint CRUD validation improves input quality and rejects protected IP literals early. Delivery-time resolution,
classification, and connection binding are authoritative.

## 3. Scope

P03 changes the webhook delivery network boundary only. It introduces:

- semantic endpoint URI parsing and normalization;
- a public-destination address policy;
- a bounded, injectable system hostname lookup;
- a policy-enforcing Apache HttpClient DNS resolver;
- a delivery-only Apache classic HTTP/1.1 transport;
- explicit DNS-to-socket binding, direct routing, TLS identity, deadline, and response-stream invariants; and
- stable delivery failure classifications integrated with the existing Attempt lifecycle.

No schema change is required. No endpoint URL snapshot is added.

## 4. Threat model

Treat the complete endpoint URL and its DNS namespace as attacker-controlled. An attacker may use:

- literal IPv4 and IPv6 addresses;
- integer, octal-looking, hexadecimal-looking, or otherwise ambiguous numeric host representations;
- compressed or bracketed IPv6;
- IPv4-mapped IPv6;
- transition prefixes that embed or derive another address;
- loopback, private, link-local, unspecified, multicast, shared, metadata, control-plane, reserved, documentation,
  benchmarking, or other special-purpose addresses;
- A, AAAA, and CNAME records under the attacker's control;
- several DNS answers, including mixed permitted and protected answers;
- DNS answers that change between CRUD, attempts, retries, and replays;
- an allowed answer during one connection followed by a protected answer for a replacement connection;
- connection failover from an unavailable first answer to later answers;
- proxy configuration as an alternate route around the destination resolver;
- redirects to a different target;
- explicit non-default ports; and
- endpoint mutation before a queued retry or replay executes.

DNSSEC does not make an attacker-controlled destination safe. A correctly authenticated DNS answer can still name a
protected address.

## 5. Approved product and architecture decisions

1. HTTP and HTTPS remain supported. HTTPS-only policy is outside P03.
2. An omitted port uses the scheme default. Any explicit TCP port from 1 through 65535 is accepted.
3. Ordinary Relay endpoints may not target private or protected destinations.
4. P03 provides no production `allow-private-addresses` property, tenant bypass, or administrator bypass.
5. A future private-network webhook product requires a separate design and isolated trust boundary.
6. Endpoint create/update performs semantic URI validation and immediately rejects protected IP literals.
7. Endpoint create/update does not require DNS resolution and remains independent of transient DNS availability.
8. No endpoint DNS-warning or pending state is introduced.
9. Delivery-time enforcement is authoritative for initial attempts, retries, and replays.
10. If a current DNS result contains any protected A or AAAA destination, Relay rejects the complete destination.
11. Destination-policy and DNS failures use the existing six-attempt lifecycle.
12. P03 adds no Attempt states and does not change retry delays, jitter, or atomic retry creation.
13. Apache classic HTTP/1.1 delivery is acceptable for P03.
14. Application enforcement is the primary boundary. Infrastructure egress restrictions are defense in depth.
15. The initial implementation adds no dnsjava dependency.
16. The initial implementation adds no Relay-managed DNS cache.

## 6. URI contract

URI normalization is separate from network security enforcement. Normalization produces an unambiguous scheme,
hostname, port, path, and query; it does not authorize a network destination. Literals are subsequently classified,
and DNS names are classified from their delivery-time result.

| Input | Required behavior |
|---|---|
| Scheme | Accept `http` and `https`, case-insensitively; normalize to lowercase. Reject every other or opaque scheme. |
| Canonical dotted IPv4 | Accept syntactically, parse without DNS, and classify at CRUD and delivery time. |
| Integer IPv4 such as `2130706433` | Reject as ambiguous/non-standard. |
| Dotted numeric host with a leading zero such as `0177.0.0.1` | Reject as ambiguous. Each canonical IPv4 octet is decimal without leading zero except `0`. |
| Hex-like IPv4 such as `0x7f000001` | Reject as ambiguous/non-standard. |
| Bracketed IPv6 | Accept if it is a valid IPv6 literal without a zone identifier; classify raw 16-byte content. |
| Unbracketed IPv6 authority | Reject. |
| IPv4-mapped IPv6 | Recognize from the raw literal before Java can collapse it; classify the embedded IPv4 for diagnostics and reject the mapped wrapper as special-purpose. |
| IDN | Convert with `IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES)`, lowercase it, and validate label and total lengths. Reject conversion or label failures. |
| One trailing DNS dot | Accept and remove it from normalized HTTP/TLS identity. Use a terminating dot only for absolute DNS lookup. |
| Multiple trailing dots or empty labels | Reject. |
| Single-label DNS name | Reject. This prevents local search-domain interpretation and excludes names such as `localhost`. |
| IPv6 zone identifier, raw or percent-encoded | Reject. |
| User-info | Reject. |
| Fragment | Reject. |
| Path and query | Allow and preserve. |
| No explicit port | Use the scheme default. |
| Port `1..65535` | Accept. |
| Port `0`, a negative explicit value, or above `65535` | Reject. |

The parser must inspect the raw authority deliberately. Java 21 `URI.getHost()` can be null for a Unicode authority
even when `getRawAuthority()` is present. The implementation must not fall back to permissive `URL` parsing.

The normalized hostname used for HTTP and TLS has no trailing dot or IPv6 brackets. URI serialization adds brackets
where IPv6 authority syntax requires them.

## 7. Address policy

### 7.1 Default rule

Ordinary endpoints may use only **ordinary globally reachable unicast** addresses.

The production classifier operates on raw four-byte or sixteen-byte addresses and immutable prefix data. It does not
classify by string, reverse DNS, or `InetAddress.isSiteLocalAddress()`.

### 7.2 Policy data

P03 commits one normalized resource derived from the IANA IPv4 and IPv6 special-purpose registries. The resource
records:

- source URLs;
- IANA registry last-updated date `2025-10-09`;
- retrieval date `2026-09-24`;
- source IPv4 CSV SHA-256
  `e3e39e76d00b1677335db8e9a805c7b9480ea2f4dc9e33f0b93cd3a905128d73`;
- source IPv6 CSV SHA-256
  `775feea0621dec8735a44fbf30f762e721e8f0a1b3ab7eb341961a88cfce2139`; and
- a Relay policy-format version.

Sources:

- `https://www.iana.org/assignments/iana-ipv4-special-registry/iana-ipv4-special-registry-1.csv`
- `https://www.iana.org/assignments/iana-ipv6-special-registry/iana-ipv6-special-registry-1.csv`

P03 does not build an automatic registry-update service. Updating the pinned resource is a reviewed security change.

### 7.3 Required coverage

Relay blocks every prefix in the two special-purpose registries, including individual special-purpose entries whose
registry metadata says they are globally reachable. Those destinations may be reachable, but they are protocol,
anycast, transition, or other special infrastructure rather than ordinary webhook receivers.

Relay also blocks explicit overlays not adequately represented by the tables:

- IPv4 multicast `224.0.0.0/4`;
- IPv6 multicast `ff00::/8`;
- deprecated IPv6 site-local space;
- deprecated IPv4-compatible IPv6 space;
- IPv4-mapped IPv6;
- NAT64 prefixes `64:ff9b::/96` and `64:ff9b:1::/48`;
- 6to4 `2002::/16`; and
- Teredo `2001::/32`.

The normalized resource expands registry cells that contain more than one prefix. Prefix matching uses address bytes
and prefix length, with exhaustive boundary tests.

### 7.4 Security versus conservative product policy

Blocking loopback, private, unique-local, link-local, unspecified, multicast, mapped protected IPv4, shared address
space, and metadata/service ranges is a direct SSRF security requirement.

Blocking documentation, benchmarking, discard-only, transition, and globally reachable special-purpose protocol
addresses is intentionally conservative Relay product policy. They are not ordinary customer webhook receivers, and
allowing them would add complexity and ambiguous behavior without a current product need.

Relay does not maintain a BGP or allocation table. An ordinary address that is currently unrouted but is not covered
by the protected/special policy may pass classification and then fail as an ordinary connection failure.

## 8. DNS design

The initial resolver pipeline is:

```text
PolicyEnforcingDnsResolver
  -> HostAddressLookup
  -> SystemHostAddressLookup
  -> bounded InetAddress.getAllByName(absolute hostname)
  -> classify complete returned result
  -> fully resolved permitted InetSocketAddress list
```

### 8.1 HostAddressLookup boundary

`HostAddressLookup` is injectable so deterministic tests can return controlled addresses without public DNS.
`SystemHostAddressLookup` is the production implementation. It preserves the JVM/OS name service Relay currently
inherits, including ordinary container `/etc/resolv.conf`, `/etc/hosts`, Docker embedded DNS, Kubernetes CoreDNS,
and deployment-specific split DNS supported by the platform resolver.

dnsjava is unnecessary for the security invariant: the binding is established by supplying Apache with only the
classified concrete addresses returned by `HostAddressLookup`, not by replacing the platform's DNS protocol client.
The injectable lookup boundary also provides deterministic DNS tests without changing production resolver semantics.
Adding dnsjava would introduce different resolver discovery, search, CNAME, timeout, and cache behavior without
strengthening the checked-address-to-socket guarantee.

DNS names are already normalized ASCII names with at least two labels. Production lookup appends one terminating dot
before calling `InetAddress.getAllByName`, making the query absolute and preventing search-domain expansion. The
terminating dot is not used for HTTP authority, Host, SNI, or certificate verification.

### 8.2 Bounded resolution

Because `InetAddress.getAllByName` exposes no per-call timeout and may ignore interruption, system lookups run on a
dedicated bounded executor. The lookup waits for the smaller of the configured DNS ceiling and the remaining delivery
deadline.

Resolver capacity and its independent timeout are node-level delivery infrastructure configuration, not security or
domain constants and not tenant/tier settings. `DeliveryDnsProperties` uses Relay's existing Spring Boot configuration
convention and exposes:

| Property | Type | Default | Startup constraint |
|---|---|---:|---|
| `relay.delivery.dns.max-concurrency` | integer | `40` | Must be greater than zero. |
| `relay.delivery.dns.queue-capacity` | integer | `40` | Must be greater than zero and produce a strictly bounded queue. |
| `relay.delivery.dns.timeout` | duration | `3s` | Must be non-null and strictly positive. |

The application fails startup when any constraint is violated. These controls apply to the Relay node as a whole;
P03 does not add dynamic reconfiguration, tenant overrides, entitlements, or paid-tier behavior. Operators may tune
the values for node capacity, but tuning must never create an unbounded executor or queue, make saturation permissive,
or allow the DNS timeout to extend the remaining total delivery deadline.

On timeout or executor saturation:

- the lookup fails closed as `DNS_RESOLUTION_FAILED`;
- the Apache resolver receives no address list;
- no socket connection begins; and
- a late platform lookup result is discarded and has no reference to an Apache request.

The executor has a fixed concurrency bound and bounded queue. Saturation must reject work rather than grow threads or
queue length without limit.

### 8.3 Caching

Relay adds no DNS cache in P03. JVM, OS, and recursive-resolver caching may still exist below
`InetAddress.getAllByName`, as it does for the current JDK client. Every address returned from that layer is classified
on every new connection path before Apache receives it.

A cached public address may be stale for availability, but it remains the public peer to which the socket connects.
It cannot turn an existing or newly connected socket into a protected peer without a new returned protected address,
which the classifier blocks.

### 8.4 Complete and mixed results

The policy applies to the complete candidate set returned by one production system lookup. If any returned address is
protected, the entire destination is rejected. If every returned address is permitted, the resolver may return all of
them to Apache for failover. It returns no unclassified candidate.

If the platform returns only one address family, Apache can use only that returned family. It has no independent
fallback to absent records.

## 9. DNS-to-socket binding

This is the primary P03 security boundary.

### 9.1 Verified Apache versions and path

Spring Boot 3.5.16 manages Apache HttpClient `5.5.2` and HttpCore `5.3.6`. Their actual source artifacts were inspected,
and a runtime binding probe exercised those binaries.

For a direct route, the relevant path is:

```text
InternalHttpClient.doExecute
  -> RoutingSupport.determineHost
  -> DefaultRoutePlanner.determineRoute
  -> InternalExecRuntime.acquireEndpoint
  -> PoolingHttpClientConnectionManager.lease
  -> PoolingHttpClientConnectionManager.connect       [only for a new socket]
  -> DefaultHttpClientConnectionOperator.connect
  -> PolicyEnforcingDnsResolver.resolve(host, port)
  -> iterate the exact returned List<InetSocketAddress>
  -> Socket.connect(current InetSocketAddress)
```

`PoolingHttpClientConnectionManagerBuilder.setDnsResolver` supplies the resolver to
`DefaultHttpClientConnectionOperator`. The operator resolves once, loops over that returned list, and calls
`Socket.connect` with each concrete list element. Connection failover advances only through that list. It neither
re-resolves the hostname nor appends system-resolved candidates.

### 9.2 Mandatory implementation invariants

The following are security requirements:

1. Production request targets are constructed from the normalized hostname, never an embedded `InetAddress`.
2. `HttpHost.getAddress()` is null for every production webhook target.
3. `PolicyEnforcingDnsResolver` overrides `resolve(String, int)` directly.
4. The resolver never returns null.
5. The resolver never returns an empty or sentinel value that triggers fallback.
6. The resolver never returns `InetSocketAddress.createUnresolved` or any address for which `isUnresolved()` is true.
7. Every returned address was classified and permitted in the same resolver invocation.
8. Mixed permitted/protected results return no address and throw a stable policy exception.
9. No code between resolver return and `Socket.connect` performs hostname resolution.

Apache has a resolver-bypass branch when `HttpHost.getAddress()` is non-null. Apache's default
`DnsResolver.resolve(host, port)` can also produce an unresolved socket address when its older `resolve(host)` method
returns null. The first two invariants deliberately make both bypasses unreachable.

### 9.3 Verification probe result

The approved probe used an unresolvable hostname, a custom resolver, one unavailable returned address, one listening
returned address, a destination trap, proxy system properties pointing at a proxy trap, and two pooled requests.

It observed:

```text
resolverCalls=1
safeAccepts=1
requests=2
destinationTrapReached=false
proxyTrapReached=false
originalHostHeader=true
```

This confirmed resolver binding, returned-list failover, original authority preservation, direct routing despite
system proxy properties, and safe reuse of the validated socket.

## 10. Routing, proxy, redirect, and retransmission invariants

The delivery-only Apache client must:

- install an explicit `DefaultRoutePlanner`;
- never call `useSystemProperties()`;
- never configure `setProxy()` or `setProxySelector()`;
- own an immutable request configuration with no proxy;
- accept no caller-provided `HttpContext` or request configuration capable of adding a proxy;
- use a `SocketConfig` with no SOCKS proxy;
- explicitly disable redirect handling; and
- explicitly disable Apache automatic retries.

`DefaultRoutePlanner` can honor a proxy explicitly placed in a per-request `RequestConfig`. The transport prevents
that path by owning request construction and context. Arbitrary proxy support is not exposed.

A 3xx response remains an ordinary non-2xx delivery failure. P03 does not follow or add redirect support.

Disabling Apache automatic retries also prevents hidden retransmission of signed POST requests. Relay's Attempt state
machine remains the sole retry owner.

## 11. TLS and HTTP identity

Connection routing separates network address from application identity:

```text
validated IP
    -> TCP socket destination

original normalized hostname
    -> route identity
    -> HTTP authority / Host
    -> TLS peer name and SNI
    -> certificate hostname verification
```

For HTTPS, `PoolingHttpClientConnectionManager` passes the original route target name to
`DefaultHttpClientConnectionOperator`, which calls the TLS strategy with that hostname after connecting the raw socket
to the validated IP. `DefaultClientTlsStrategy.createDefault()` uses normal platform trust,
`HostnameVerificationPolicy.BOTH`, HTTPS endpoint identification, and the default hostname verifier.

P03 must not use `NoopHostnameVerifier`, a trust-all context, disabled endpoint identification, attacker-selected
Host manipulation, or a URI rewritten to the chosen IP.

TLS integration tests must observe SNI and verify both a matching and wrong-host certificate.

## 12. Connection pooling

A pooled connection may be reused only if Apache considers it open and consistent and the route identity matches.
Its TCP peer is the address validated when that socket was created. DNS changes cannot retarget an established socket.

Reusing such a socket is safe and avoids unnecessary handshakes. When the socket is absent, stale, expired, closed, or
discarded, its replacement must return through `PoolingHttpClientConnectionManager.connect` and the enforcing resolver.

Pool limits, idle eviction, and connection time-to-live are operational controls. They must not create an alternate
connection manager or bypass path.

## 13. P01 preservation: exact signed and transmitted bytes

`DeliveryWorker` continues to create one authoritative UTF-8 `byte[]` from the stored JSON and passes that same array
to `HmacSigner` and the HTTP transport.

The Apache transport uses that exact array as a `ByteArrayEntity`. HttpCore writes the selected byte-array range
directly to the request stream; it performs no character encoding or JSON serialization.

P03 preserves:

- the existing HMAC input and output format;
- `relay-id`, `relay-timestamp`, and `relay-signature` names and values;
- the exact body bytes signed and sent;
- JSON media type compatibility; and
- one Relay-controlled transmission per Attempt.

Use a content type that emits `application/json` without unintentionally changing its parameters. Apache automatic
retries remain disabled.

## 14. P02 preservation: bounded response diagnostics

P02's limits remain authoritative:

- read no more than 10,241 response bytes;
- retain at most 10,240 bytes plus the existing truncation marker;
- preserve the database persistence bound; and
- do not trust `Content-Length` to decide how much to consume.

Apache wraps streaming response entities in `EofSensorInputStream` through `ResponseEntityProxy`.

The verified target behavior for an oversized response is to call `EofSensorInputStream.abort()`. Apache's
stream-abort path discards the endpoint and does not drain the unread remainder or return the connection to the pool.
Ordinary `close()` must not be called first because Apache's graceful stream-close path may consume the remainder for
connection reuse.

Before the response-consumer abstraction is finalized, implementation must verify against the actual HttpClient
5.5.2 / HttpCore 5.3.6 APIs that the owner of response execution can invoke this abort behavior cleanly. It must not
require an unsafe cast, depend on an undocumented concrete stream type, or leak Apache implementation classes through
an otherwise generic response-consumer contract. If it cannot cleanly distinguish normal EOF/reuse, truncation/abort,
and body-read failure/discard, implementation stops and reports the observed API/runtime behavior and the smallest
alternative ownership boundary. That alternative may rearrange internal abstractions but may not change P02's
observable bounds or connection-discard behavior.

For a response that reaches EOF within the limit, the stream completes normally and Apache may reuse the connection.

## 15. Total delivery deadline

One monotonic 15-second deadline covers:

- connection-pool acquisition;
- DNS lookup and classification;
- TCP connect;
- TLS handshake;
- request transmission;
- response headers; and
- bounded response capture.

Component timeouts use the remaining duration, not independent 15-second budgets. A scheduled absolute-deadline action
cancels the Apache `HttpUriRequestBase` and is cancelled in `finally` when the exchange finishes.

In Apache 5.5.2, cancellation cancels a pending lease or causes `InternalExecRuntime.cancel()` to discard an acquired
endpoint. Cancellation requested before a dependency is attached is propagated when `HttpUriRequestBase.setDependency`
later receives it.

Request cancellation is not relied upon to interrupt a platform DNS call. The bounded DNS wrapper independently
stops waiting, throws without returning addresses, and discards any late result. Therefore an abandoned DNS operation
cannot proceed into `Socket.connect`.

`DeliveryDeadlineContext` is a narrow adapter for the fact that Apache's synchronous `DnsResolver` SPI does not carry
Relay's delivery deadline. `WebhookHttpTransport` opens the scope immediately around Apache execution, and
`PolicyEnforcingDnsResolver` reads it only during the synchronous resolver callback. It is not Relay's general deadline
or request-context propagation mechanism and must not be used by unrelated application code.

The scope is removed in `finally` after success, DNS failure, destination-policy failure, transport failure, delivery
timeout/cancellation, or any other exception while the scope is active. A later exchange on the same thread cannot
observe a prior deadline. Opening a nested scope or resolving without a scope is an internal invariant violation; the
adapter must not silently replace, inherit, or invent a deadline.

## 16. Failure semantics

Transport and destination failures normalize to stable bounded codes:

| Code | Meaning | Connection behavior |
|---|---|---|
| `DESTINATION_INVALID` | Persisted URI fails semantic parsing or scheme/port rules. | No DNS or socket. |
| `DNS_RESOLUTION_FAILED` | Lookup fails, times out, returns no candidates, or resolver executor is saturated. | No socket. |
| `DESTINATION_POLICY_BLOCKED` | Literal or any current DNS candidate is protected/special. | No socket. |
| `DELIVERY_TIMEOUT` | The single 15-second exchange deadline expires. | Pending work cancelled; acquired connection discarded. |
| `TRANSPORT_FAILURE` | A permitted destination fails during connect, TLS, write, headers, or bounded read. | Only validated destinations were attempted. |

Persist `CODE: sanitized message` through the existing bounded `last_error` field. Do not include user-info,
credentials, unbounded resolver output, or stack traces in customer-visible diagnostics.

Every modeled external/destination/transport failure follows the current six-attempt lifecycle:

- attempts before number six become `FAILED_RETRYING` and atomically create their scheduled child;
- attempt six becomes `DEAD`; and
- existing delay, jitter, dead-letter, recovery, and notification behavior remains unchanged.

`WebhookDeliveryException` normalizes only expected exchange failures: invalid persisted destinations; DNS lookup
failure, timeout, or executor saturation; destination-policy rejection; TCP/connect failure; TLS or hostname
verification failure; request/response I/O failure; total exchange timeout/cancellation; and bounded response-read
failure. Representative failures from each layer must enter the failure path after claim.

P03 must not add a broad `catch (Exception)` or `catch (RuntimeException)` that converts programming defects,
violated internal invariants, null dereferences, or unrelated application bugs to `TRANSPORT_FAILURE`. Unexpected
internal defects remain visible as defects and do not silently consume the customer's retry budget. Consequently, the
P03 guarantee is that no **expected modeled exchange failure** after claim escapes the existing failure path—not that
P03 can prevent every arbitrary defect from leaving work in progress. Execution ownership, stale/stranded execution,
fencing, and related recovery correctness remain P04 responsibilities.

## 17. Existing endpoints and rollout

### 17.1 Compatibility

- Existing public HTTP and HTTPS endpoints continue to deliver over HTTP/1.1.
- Existing protected endpoints remain stored but fail closed on their next attempt.
- Invalid URLs admitted by old validation become `DESTINATION_INVALID` delivery failures.
- Pending retries resolve and enforce the endpoint's current URL when they execute.
- Manual replays do the same.
- No database migration or bulk URL rewrite is required.

HTTP/1.1 replaces the JDK client's HTTP/2 preference for webhook delivery. Receivers are expected to accept HTTP/1.1;
the rollout audit should identify any documented HTTP/2-only receiver requirement before activation.

### 17.2 Rollout

Before enforcement, run an advisory audit of stored endpoint URI syntax and current destination policy results. The
audit does not authorize future delivery because DNS may change after it runs.

Expose metrics for at least:

- destination-invalid failures;
- DNS resolution failures and timeouts;
- policy blocks by address family/category;
- mixed-answer blocks;
- resolver executor saturation;
- total delivery timeouts;
- truncated/aborted responses; and
- connection pool acquisition/connect failures.

Log endpoint and attempt identifiers with stable failure codes. Avoid logging signing secrets, user-info, or complete
untrusted URLs containing sensitive query data.

Production infrastructure should additionally deny webhook workload egress to private, link-local, metadata, cluster
service, and control-plane networks except separately required application dependencies. Such restrictions are defense
in depth and do not replace the application binding.

## 18. Non-goals

P03 does not implement:

- P04 stale-execution fencing;
- P05 replay allocation changes;
- P06 scheduler isolation;
- P08 idempotency or any later readiness project;
- a private-network webhook product or protected-address bypass;
- HTTPS-only policy;
- a webhook port allowlist;
- endpoint DNS-warning/pending states;
- custom DNS caching;
- redirect following;
- generic or customer-configurable proxy support;
- custom certificate authorities or insecure TLS;
- HTTP/2 delivery;
- broad retry-policy redesign;
- URL snapshots or destination history;
- API keys, quotas, entitlements, billing, or service decomposition; or
- unrelated repair of the P00 shared-Spring-context scheduler-isolation test issue.

## 19. Security invariants checklist

Future webhook transport changes must preserve all of the following:

- [ ] Every newly established webhook socket uses an address approved for that connection.
- [ ] Hostname validation and connection use the same concrete address list; there is no independent second lookup.
- [ ] Every current DNS candidate is classified, and any protected candidate rejects the complete result.
- [ ] Production `HttpHost` targets have `getAddress() == null` before the enforcing resolver runs.
- [ ] The resolver never returns null, unresolved addresses, fallback sentinels, or unclassified addresses.
- [ ] Apache failover is limited to the exact validated list.
- [ ] New and replacement pooled sockets re-enter resolver enforcement; reused sockets retain their validated peer.
- [ ] Webhook delivery cannot acquire an HTTP or SOCKS proxy from callers, environment, or system properties.
- [ ] Redirect handling and Apache automatic retries remain disabled.
- [ ] The TCP peer uses the validated IP while HTTP Host, TLS SNI, and certificate verification use the original
      normalized hostname.
- [ ] Standard certificate chain and hostname verification remain enabled.
- [ ] The exact byte array signed by P01 is the array transmitted; headers and signature format remain unchanged.
- [ ] Response consumption remains at most 10,241 bytes; oversized bodies abort rather than drain.
- [ ] Small responses reach EOF normally and may return their connection to the pool.
- [ ] One monotonic 15-second deadline bounds DNS through bounded response capture.
- [ ] DNS timeout, executor saturation, malformed destinations, policy blocks, and transport failures fail closed.
- [ ] Resolver concurrency and queue capacity remain validated and strictly bounded; configured DNS timeout is
      positive and never exceeds the remaining delivery deadline.
- [ ] Every expected modeled destination/exchange failure after Attempt claim enters the existing failure path.
- [ ] Unexpected programming defects are not converted to customer endpoint failures by a catch-all.
- [ ] Deadline context is resolver-adapter-only and is removed on every exit before the thread is reused.
- [ ] The existing six-attempt, atomic retry, acknowledgment, recovery, and identity semantics remain intact.
