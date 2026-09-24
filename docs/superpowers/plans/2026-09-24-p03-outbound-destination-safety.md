# P03 Outbound Destination Safety / SSRF Protection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure every newly established ordinary-customer webhook socket connects only to an address approved by Relay's public-destination policy for that connection.

**Architecture:** A shared semantic URI parser validates CRUD and delivery inputs. A bounded system lookup feeds a policy-enforcing Apache HttpClient 5 resolver, and Apache connects only to the resolver's fully classified concrete address list while retaining the original hostname for HTTP and TLS identity. A delivery-specific transport preserves P01 exact bytes, P02 bounded response handling, the total deadline, and the existing Attempt lifecycle.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Web 6.2.19, Apache HttpClient 5.5.2, HttpCore 5.3.6, Hibernate Validator, JUnit 5, MockWebServer/local sockets, PostgreSQL/Testcontainers where existing lifecycle tests require it.

**Spec:** `docs/superpowers/specs/2026-09-24-p03-outbound-destination-safety-design.md`

## Global Constraints

- Continue supporting HTTP and HTTPS.
- Accept omitted scheme-default ports and explicit TCP ports `1..65535`; reject zero and values above `65535`.
- Ordinary endpoints cannot target private, protected, or special-purpose destinations.
- Do not add an allow-private switch, tenant bypass, private-network feature, dnsjava, or Relay-managed DNS cache.
- Endpoint CRUD resolves no DNS hostnames; it validates syntax/semantics and classifies literals only.
- Delivery-time enforcement is authoritative for initial attempts, retries, and replays.
- Any protected address in the current returned candidate set rejects the entire destination.
- Production `HttpHost.getAddress()` must be null before the enforcing resolver runs.
- The Apache resolver returns only non-null, non-empty, fully resolved, policy-approved `InetSocketAddress` values.
- Use Apache classic HTTP/1.1. Disable redirects, automatic HTTP retries, HTTP proxies, SOCKS proxies, and system-property routing.
- Preserve normal TLS trust, SNI, and hostname verification against the original normalized hostname.
- Preserve P01's one authoritative signed/transmitted byte array and current headers/signature format.
- Preserve P02's 10,241-byte maximum read, 10,240-byte retention, persistence bound, and abort-not-drain behavior.
- Preserve one monotonic 15-second deadline from pool acquisition through bounded body capture.
- Use `DeliveryDeadlineContext` only as the scoped adapter into Apache's synchronous resolver SPI; clear it on every
  exit and never use it as general Relay context propagation.
- Normalize expected destination/exchange failures only. Do not catch arbitrary programming defects as
  `TRANSPORT_FAILURE`.
- Preserve the current six-attempt lifecycle, retry timing/jitter, atomic parent failure plus retry creation, asynchronous acknowledgment, recovery behavior, and domain identities.
- Do not implement P04/P05/P06/P08+, URL snapshots, redirect support, custom CA/insecure TLS, generic proxy support, or retry-policy redesign.
- Do not weaken tests or absorb the existing P00 shared-Spring-context scheduler-isolation issue into P03.

## Review Focus

- Ambiguous numeric hosts must fail semantic validation rather than reach the platform resolver; Task A tests integer, leading-zero, and hex-like forms.
- A resolver returning null, an empty list, or an unresolved address must fail closed; Task C tests all three resolver-bypass forms.
- Request/system configuration must not introduce HTTP or SOCKS proxies; Task D tests Java proxy properties and explicit no-SOCKS configuration.
- A DNS timeout that completes late must never provide candidates to the abandoned Apache request; Task C uses latches to prove the late result is discarded.
- A truncated Apache entity must abort before any graceful close can drain it; Task F counts actual server bytes and tests connection replacement.
- The initial resolver sizing in Task C (40 workers, 40 queued lookups, 3-second DNS ceiling) is supplied through
  validated node-level `relay.delivery.dns.*` application properties. Changing those operational values must preserve
  bounded capacity, fail-closed saturation, the independent DNS ceiling, and the single total delivery deadline.

## Planned file structure

| Path | Responsibility |
|---|---|
| `endpoint/domain/IpLiteral.java` | Immutable raw 4-byte/16-byte literal representation. |
| `endpoint/domain/ParsedWebhookUri.java` | Normalized URI, hostname, effective port, and optional parsed literal. |
| `endpoint/domain/WebhookUriParser.java` | Exact URI contract with no DNS lookup. |
| `endpoint/api/validation/ValidWebhookUrl.java` | Bean Validation annotation for endpoint DTO fields. |
| `endpoint/api/validation/WebhookUrlValidator.java` | Delegates DTO validation to `WebhookUriParser`. |
| `deliveryengine/destination/PublicDestinationAddressPolicy.java` | Raw-byte ordinary-global-unicast policy. |
| `deliveryengine/destination/SpecialPurposeAddressCatalog.java` | Loads and validates pinned prefix data. |
| `deliveryengine/destination/HostAddressLookup.java` | Injectable hostname-to-address boundary. |
| `deliveryengine/destination/SystemHostAddressLookup.java` | Bounded platform lookup using absolute names. |
| `deliveryengine/destination/PolicyEnforcingDnsResolver.java` | Apache resolver returning only approved concrete socket addresses. |
| `deliveryengine/config/DeliveryDnsProperties.java` | Validated node-level resolver capacity and timeout configuration. |
| `deliveryengine/http/DeliveryDeadline.java` | Monotonic total-deadline value and remaining-budget calculation. |
| `deliveryengine/http/DeliveryDeadlineContext.java` | Resolver-SPI-only scoped adapter for the current delivery deadline. |
| `deliveryengine/http/WebhookHttpTransport.java` | Worker-facing delivery-only HTTP abstraction. |
| `deliveryengine/http/ApacheWebhookHttpTransport.java` | Direct Apache request, deadline, TLS identity, and response orchestration. |
| `deliveryengine/http/WebhookResponseBodyConsumer.java` | Response consumption boundary used by the transport. |
| `deliveryengine/http/BoundedApacheResponseBodyConsumer.java` | P02 EOF/reuse and abort-on-truncation behavior. |
| `deliveryengine/http/WebhookDeliveryException.java` | Stable bounded failure code and sanitized diagnostic. |

---

### Task A: URI semantics and endpoint validation

**Dependencies:** None.

**Files:**
- Create: `src/main/java/com/example/relay/endpoint/domain/IpLiteral.java`
- Create: `src/main/java/com/example/relay/endpoint/domain/ParsedWebhookUri.java`
- Create: `src/main/java/com/example/relay/endpoint/domain/InvalidWebhookUriException.java`
- Create: `src/main/java/com/example/relay/endpoint/domain/WebhookUriParser.java`
- Create: `src/main/java/com/example/relay/endpoint/api/validation/ValidWebhookUrl.java`
- Create: `src/main/java/com/example/relay/endpoint/api/validation/WebhookUrlValidator.java`
- Modify: `src/main/java/com/example/relay/endpoint/api/dto/EndpointCreateDto.java`
- Modify: `src/main/java/com/example/relay/endpoint/api/dto/EndpointUpdateDto.java`
- Create: `src/test/java/com/example/relay/endpoint/domain/WebhookUriParserTest.java`
- Create: `src/test/java/com/example/relay/endpoint/api/validation/WebhookUrlValidatorTest.java`
- Modify: `src/test/java/com/example/relay/endpoint/api/EndpointControllerTest.java`

**Interfaces:**

```java
public record IpLiteral(byte[] addressBytes) {
    public IpLiteral { addressBytes = addressBytes.clone(); }
    @Override public byte[] addressBytes() { return addressBytes.clone(); }
}

public record ParsedWebhookUri(
        URI normalizedUri,
        String scheme,
        String normalizedHost,
        int effectivePort,
        IpLiteral literalAddress) {
    public boolean isLiteral() { return literalAddress != null; }
}

public final class WebhookUriParser {
    public ParsedWebhookUri parse(String rawUrl) throws InvalidWebhookUriException;
}
```

**Responsibility:** Establish one semantic parser used by endpoint CRUD and delivery execution. It normalizes but does
not resolve hostname DNS. Literal parsing must retain raw IPv6 bytes long enough to recognize mapped addresses.

**Important invariants:**

- Canonical dotted IPv4 has four decimal octets, no ambiguous leading zero, and values `0..255`.
- Integer, octal-looking, and hex-like numeric hosts fail before `InetAddress` is called.
- IPv6 must be bracketed in the URI and cannot contain a zone identifier.
- IDNs use `IDN.USE_STD3_ASCII_RULES` and become lowercase ASCII.
- Exactly one trailing DNS dot is removed from normalized HTTP/TLS identity.
- Single-label hostnames, user-info, fragments, port zero, and ports above `65535` fail.
- Paths and queries survive normalization unchanged.

- [ ] **Step A1: Write table-driven parser tests**

Create parameterized accepted cases for canonical IPv4, bracketed IPv6, ASCII DNS, Unicode IDN, one trailing dot,
paths, queries, HTTP/HTTPS case normalization, default ports, and explicit ports 1 and 65535.

Create parameterized rejected cases for:

```java
Stream.of(
    "http://2130706433/",
    "http://0177.0.0.1/",
    "http://0x7f000001/",
    "http://2001:db8::1/",
    "http://[fe80::1%25eth0]/",
    "http://user:pass@example.com/",
    "http://example.com/#fragment",
    "http://localhost/",
    "http://example.com:0/",
    "http://example.com:65536/",
    "ftp://example.com/"
)
```

Assert that parsing a DNS hostname does not call any resolver by keeping `WebhookUriParser` free of a resolver
dependency and checking its constructor signature.

- [ ] **Step A2: Run tests and confirm the pre-implementation failure**

Run:

```bash
./mvnw test -Dtest=WebhookUriParserTest,WebhookUrlValidatorTest
```

Expected: compilation failure because the parser and validator do not exist.

- [ ] **Step A3: Implement the parser and immutable value types**

Parse the raw authority explicitly. Use `URI` for overall structure, strict authority splitting for IDN/IPv6 handling,
and byte-level literal parsers. Do not call `InetAddress.getByName` or `InetAddress.getAllByName` in this package.

For a DNS host, reconstruct a normalized `URI` from lowercase scheme, ASCII hostname without a trailing dot, validated
port, original raw path, and original raw query. For IPv6 serialization, bracket the normalized literal in the URI but
store `normalizedHost` without brackets.

- [ ] **Step A4: Replace DTO URL annotations**

Replace `@URL` and the `^https?://` regex with `@ValidWebhookUrl`. Retain `@NotBlank` on create; update remains nullable
and the validator treats null as valid so `@ValidEndpointUpdate` continues to control an empty patch.

- [ ] **Step A5: Add controller-boundary tests and verify**

Assert POST/PATCH reject protected-looking ambiguous syntax and malformed ports with HTTP 400, accept syntactically
valid public DNS names without performing DNS, and preserve existing DTO behavior.

Run:

```bash
./mvnw test -Dtest=WebhookUriParserTest,WebhookUrlValidatorTest,EndpointControllerTest,EndpointUpdateValidatorTest
```

Expected: PASS.

- [ ] **Step A6: Commit checkpoint**

```bash
git add src/main/java/com/example/relay/endpoint src/test/java/com/example/relay/endpoint
git commit -m "feat: define webhook URI semantics"
```

---

### Task B: Raw-byte public destination policy

**Dependencies:** Task A supplies `IpLiteral.addressBytes()`.

**Files:**
- Create: `src/main/resources/security/iana-special-purpose-addresses-2025-10-09.txt`
- Create: `src/main/java/com/example/relay/deliveryengine/destination/IpPrefix.java`
- Create: `src/main/java/com/example/relay/deliveryengine/destination/AddressPolicyDecision.java`
- Create: `src/main/java/com/example/relay/deliveryengine/destination/SpecialPurposeAddressCatalog.java`
- Create: `src/main/java/com/example/relay/deliveryengine/destination/PublicDestinationAddressPolicy.java`
- Create: `src/test/java/com/example/relay/deliveryengine/destination/SpecialPurposeAddressCatalogTest.java`
- Create: `src/test/java/com/example/relay/deliveryengine/destination/PublicDestinationAddressPolicyTest.java`
- Modify: `src/main/java/com/example/relay/endpoint/api/validation/WebhookUrlValidator.java`
- Modify: `src/test/java/com/example/relay/endpoint/api/validation/WebhookUrlValidatorTest.java`

**Interfaces:**

```java
public record AddressPolicyDecision(boolean allowed, String category) {
    public static AddressPolicyDecision allow();
    public static AddressPolicyDecision block(String category);
}

public final class PublicDestinationAddressPolicy {
    public AddressPolicyDecision evaluate(byte[] addressBytes);
    public void requireAllowed(byte[] addressBytes) throws DestinationPolicyBlockedException;
}
```

**Responsibility:** Decide whether raw IPv4/IPv6 bytes represent ordinary globally reachable unicast. CRUD uses it
only for literals; the delivery resolver uses it for every returned address.

**Important invariants:** All IANA special-purpose entries are blocked regardless of their individual globally
reachable flag. Multicast and transition overlays are explicit. Mapped IPv6 cannot collapse into an unexamined IPv4.

- [ ] **Step B1: Add the pinned normalized policy resource**

Use a line-oriented format with metadata comments followed by `prefix|category`, expanding multi-prefix IANA cells.
Record the exact source URLs, last-updated date, retrieval date, hashes from the spec, and policy format version.

Add explicit overlay rows for IPv4/IPv6 multicast, deprecated site-local/compatible space, mapped IPv6, NAT64, 6to4,
and Teredo.

- [ ] **Step B2: Write failing catalog and classifier tests**

Tests must:

- load every non-comment resource row;
- assert valid address width and prefix length;
- reject duplicate normalized prefixes;
- classify the first address, last address, and one adjacent outside address where meaningful;
- cover loopback, RFC1918, CGNAT, link-local, unspecified, multicast, documentation, benchmarking, ULA, IPv6 link-local,
  mapped loopback, mapped public IPv4, NAT64, 6to4, and Teredo; and
- allow representative ordinary public IPv4 and IPv6 byte arrays without contacting the network.

Expected pre-implementation command:

```bash
./mvnw test -Dtest=SpecialPurposeAddressCatalogTest,PublicDestinationAddressPolicyTest
```

Expected: compilation/resource failure.

- [ ] **Step B3: Implement immutable prefix matching**

`IpPrefix.contains(byte[])` compares complete bytes plus the remaining mask byte. It rejects mismatched address widths.
Load and validate the catalog once into an immutable list. No reverse DNS and no `InetAddress` classification helpers
are authoritative.

- [ ] **Step B4: Enforce literal policy during CRUD**

Inject `PublicDestinationAddressPolicy` into `WebhookUrlValidator`. If `ParsedWebhookUri.isLiteral()`, reject a blocked
decision. Do not call DNS for a hostname.

Add controller tests showing `127.0.0.1`, `[::1]`, RFC1918, ULA, link-local, unspecified, and mapped literals receive
HTTP 400 while a syntactically valid DNS hostname is accepted even when deliberately nonexistent.

- [ ] **Step B5: Verify and commit**

```bash
./mvnw test -Dtest=SpecialPurposeAddressCatalogTest,PublicDestinationAddressPolicyTest,WebhookUrlValidatorTest,EndpointControllerTest
git add src/main/resources/security src/main/java/com/example/relay/deliveryengine/destination src/main/java/com/example/relay/endpoint/api/validation src/test/java/com/example/relay/deliveryengine/destination src/test/java/com/example/relay/endpoint
git commit -m "feat: classify public webhook destinations"
```

Expected: PASS before commit.

---

### Task C: Bounded resolver boundary

**Dependencies:** Tasks A and B.

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/example/relay/deliveryengine/http/DeliveryDeadline.java`
- Create: `src/main/java/com/example/relay/deliveryengine/http/DeliveryDeadlineContext.java`
- Create: `src/main/java/com/example/relay/deliveryengine/destination/HostAddressLookup.java`
- Create: `src/main/java/com/example/relay/deliveryengine/destination/SystemHostAddressLookup.java`
- Create: `src/main/java/com/example/relay/deliveryengine/destination/DestinationPolicyBlockedException.java`
- Create: `src/main/java/com/example/relay/deliveryengine/destination/DnsResolutionException.java`
- Create: `src/main/java/com/example/relay/deliveryengine/destination/PolicyEnforcingDnsResolver.java`
- Create: `src/main/java/com/example/relay/deliveryengine/config/DeliveryDnsProperties.java`
- Create: `src/main/java/com/example/relay/deliveryengine/config/DeliveryDnsExecutorConfig.java`
- Modify: `src/main/resources/application.properties`
- Create: `src/test/java/com/example/relay/deliveryengine/config/DeliveryDnsPropertiesBindingTest.java`
- Create: `src/test/java/com/example/relay/deliveryengine/config/DeliveryDnsPropertiesValidationTest.java`
- Create: `src/test/java/com/example/relay/deliveryengine/config/DeliveryDnsPropertiesConfigurationKeysTest.java`
- Create: `src/test/java/com/example/relay/deliveryengine/destination/SystemHostAddressLookupTest.java`
- Create: `src/test/java/com/example/relay/deliveryengine/destination/PolicyEnforcingDnsResolverTest.java`
- Create: `src/test/java/com/example/relay/deliveryengine/http/DeliveryDeadlineContextTest.java`

**Interfaces:**

```java
public interface HostAddressLookup {
    List<InetAddress> lookup(String absoluteHostname, DeliveryDeadline deadline)
            throws DnsResolutionException;
}

public final class PolicyEnforcingDnsResolver implements DnsResolver {
    @Override
    public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException;
    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException;
    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException;
}
```

`resolve(String)` must not provide a fallback implementation: throw an `IllegalStateException` if Apache unexpectedly
uses it. `resolve(String, int)` is the only supported connection path.

The node-level configuration contract is:

```properties
relay.delivery.dns.max-concurrency=40
relay.delivery.dns.queue-capacity=40
relay.delivery.dns.timeout=3s
```

`DeliveryDnsProperties` follows the repository's `@ConfigurationProperties` plus startup-validation convention.
Both integer values and the duration must be strictly positive. P03 introduces no tenant, tier, or dynamic override.

**Responsibility:** Convert an absolute hostname or already-parsed literal into one complete, validated, fully resolved
socket-address list within the current delivery deadline.

- [ ] **Step C1: Add the Boot-managed Apache dependency**

Add `org.apache.httpcomponents.client5:httpclient5` with no explicit version. Do not add dnsjava.

Verify:

```bash
./mvnw dependency:tree -Dincludes=org.apache.httpcomponents.client5:httpclient5,org.apache.httpcomponents.core5:httpcore5
```

Expected: HttpClient `5.5.2` and HttpCore `5.3.6`.

- [ ] **Step C2: Write failing resolver tests**

Use fake `HostAddressLookup` instances. Cover:

```java
@Test void protectedOnlyResultThrowsPolicyBlocked();
@Test void mixedPublicAndProtectedResultRejectsTheWholeSet();
@Test void onePermittedResultReturnsOneResolvedSocketAddress();
@Test void multiplePermittedResultsPreserveOnlyTheValidatedSetAndOrder();
@Test void nullLookupResultFailsClosed();
@Test void emptyLookupResultFailsClosed();
@Test void unresolvedSocketAddressCanNeverBeReturned();
@Test void hostnameLookupReceivesExactlyOneTerminatingDot();
@Test void literalDoesNotEnterSystemHostnameLookup();
@Test void deadlineExpiryBeforeLookupFailsWithoutCallingLookup();
```

Add binding, configuration-key, and startup-validation tests proving the defaults and explicit overrides bind;
zero and negative concurrency/capacity fail; null, zero, and negative timeouts fail; and the three canonical keys are
present in `application.properties` without legacy aliases.

Run and expect compilation failure:

```bash
./mvnw test -Dtest=DeliveryDnsPropertiesBindingTest,DeliveryDnsPropertiesValidationTest,DeliveryDnsPropertiesConfigurationKeysTest,DeliveryDeadlineContextTest,SystemHostAddressLookupTest,PolicyEnforcingDnsResolverTest
```

- [ ] **Step C3: Implement the monotonic deadline context**

`DeliveryDeadline.start(Duration.ofSeconds(15), LongSupplier nanoTime)` stores an absolute monotonic deadline.
`remaining()` returns a non-negative duration. `DeliveryDeadlineContext` uses a scoped `ThreadLocal` because Apache
classic invokes the resolver synchronously on the request thread. Opening a nested or missing scope is an error; every
scope clears in `close()`.

Keep this class package-scoped where practical and use it only between `ApacheWebhookHttpTransport` execution and
`PolicyEnforcingDnsResolver`. It is not a general request/deadline context. Unit tests prove normal and exceptional
close remove the value, nested scopes fail, missing scopes fail, and a second scope on the same thread cannot observe
the first scope's deadline.

- [ ] **Step C4: Implement bounded system lookup**

Construct a dedicated `ThreadPoolExecutor` from `DeliveryDnsProperties`: fixed concurrency defaults to 40, bounded
queue capacity defaults to 40, threads are named daemons, and rejection uses abort policy.
`SystemHostAddressLookup.lookup()` submits only `InetAddress.getAllByName(absoluteHostname)`, waits for
`min(properties.timeout(), deadline.remaining())`, and converts timeout, rejection, interruption, unknown host, null,
and empty results to `DnsResolutionException`. The DNS property never overrides or extends the remaining total
delivery deadline.

Cancel the future on timeout, but do not claim native resolver cancellation. After `Future.get`, re-check the deadline.
A late result is ignored because only the waiting method can return candidates to Apache.

- [ ] **Step C5: Add deterministic timeout, late-completion, and saturation tests**

Use latches rather than sleeps:

- occupy all lookup workers and fill the queue; assert the next lookup immediately fails closed;
- release workers and verify executor recovery;
- repeat saturation tests with small values supplied through `DeliveryDnsProperties`, proving configured concurrency
  and queue capacity are actually enforced;
- let the caller time out, then release the fake platform lookup; assert no returned-list observer is invoked; and
- verify interruption restores the interrupt flag and returns `DNS_RESOLUTION_FAILED`.

- [ ] **Step C6: Implement enforcing resolution**

For a literal, parse raw bytes again using `WebhookUriParser`, classify, and return one resolved socket address only if
allowed. For a hostname, call `HostAddressLookup` with `normalizedHost + "."`, classify every result first, reject the
complete set if any decision blocks, then create the returned `InetSocketAddress` list.

Before return, assert every list element is non-null, has the requested port, and `isUnresolved() == false`.

- [ ] **Step C7: Verify and commit**

```bash
./mvnw test -Dtest=DeliveryDnsPropertiesBindingTest,DeliveryDnsPropertiesValidationTest,DeliveryDnsPropertiesConfigurationKeysTest,DeliveryDeadlineContextTest,SystemHostAddressLookupTest,PolicyEnforcingDnsResolverTest,PublicDestinationAddressPolicyTest
git add pom.xml src/main/resources/application.properties src/main/java/com/example/relay/deliveryengine/destination src/main/java/com/example/relay/deliveryengine/http/DeliveryDeadline.java src/main/java/com/example/relay/deliveryengine/http/DeliveryDeadlineContext.java src/main/java/com/example/relay/deliveryengine/config/DeliveryDnsProperties.java src/main/java/com/example/relay/deliveryengine/config/DeliveryDnsExecutorConfig.java src/test/java/com/example/relay/deliveryengine/config src/test/java/com/example/relay/deliveryengine/destination src/test/java/com/example/relay/deliveryengine/http/DeliveryDeadlineContextTest.java
git commit -m "feat: bind DNS resolution to destination policy"
```

Expected: PASS.

---

### Task D: Construct the direct Apache client

**Dependencies:** Task C provides the Apache dependency and resolver.

**Files:**
- Modify: `src/main/java/com/example/relay/deliveryengine/config/DeliveryHttpClientConfig.java`
- Create: `src/test/java/com/example/relay/deliveryengine/config/DeliveryHttpClientSecurityConfigTest.java`
- Create: `src/test/java/com/example/relay/deliveryengine/http/ApacheDnsSocketBindingIntegrationTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/config/DeliveryHttpClientPinningTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/config/DeliveryHttpClientConnectTimeoutTest.java`

**Produces:**

```java
@Bean(destroyMethod = "close")
CloseableHttpClient deliveryApacheHttpClient(...);

@Bean(destroyMethod = "close")
PoolingHttpClientConnectionManager deliveryConnectionManager(...);
```

**Responsibility:** Create exactly one direct delivery connection manager whose every new hostname-backed socket uses
`PolicyEnforcingDnsResolver`.

**Important invariants:** The request target contains a hostname but no embedded address. No HTTP/SOCKS proxy, redirect,
or Apache retry layer is enabled.

- [ ] **Step D1: Write failing configuration invariants**

Tests inspect/build a request for `https://webhook.test/path` and assert:

- route target hostname is `webhook.test`;
- `HttpHost.getAddress()` is null;
- default `RequestConfig.getProxy()` is null;
- default `SocketConfig.getSocksProxyAddress()` is null; and
- client construction never calls `useSystemProperties` through a test that sets `http.proxyHost` and observes a trap.

- [ ] **Step D2: Build the connection manager and client**

Use `PoolingHttpClientConnectionManagerBuilder.setDnsResolver(policyResolver)`, bounded pool sizes compatible with the
current 40-worker aggregate capacity, explicit finite connection TTL/idle eviction, standard
`DefaultClientTlsStrategy.createDefault()`, and explicit no-SOCKS `SocketConfig`.

Build `HttpClients.custom()` with:

```java
.setConnectionManager(connectionManager)
.setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
.disableRedirectHandling()
.disableAutomaticRetries()
```

Do not call `useSystemProperties`, `setProxy`, or `setProxySelector`. The later transport creates no arbitrary
`HttpContext` and no request-specific proxy configuration.

- [ ] **Step D3: Promote the approved binding probe into an integration test**

Adapt the logic from `target/p03-verification/ApacheBindingProbe.java` into
`ApacheDnsSocketBindingIntegrationTest`. Use an unresolvable hostname, a custom resolver returning first an unavailable
test address and then the permitted listener, destination/proxy traps, and two requests over one pooled connection.

Assert:

```text
resolver calls = 1
accepted sockets = 1
received requests = 2
destination trap = 0
proxy trap = 0
Host = original hostname and port
```

- [ ] **Step D4: Test retry and redirect disabling**

For automatic retry, have a server accept and drop a POST connection after reading it; assert Apache does not open a
second socket. For redirects, return 302 with a trap `Location`; assert the trap receives no request and the 302 is
returned to the caller.

- [ ] **Step D5: Verify and commit**

```bash
./mvnw test -Dtest=DeliveryHttpClientSecurityConfigTest,ApacheDnsSocketBindingIntegrationTest,DeliveryHttpClientPinningTest,DeliveryHttpClientConnectTimeoutTest
git add src/main/java/com/example/relay/deliveryengine/config/DeliveryHttpClientConfig.java src/test/java/com/example/relay/deliveryengine/config src/test/java/com/example/relay/deliveryengine/http
git commit -m "feat: configure direct enforcing webhook client"
```

Expected: PASS.

---

### Task E: Add the delivery-specific Apache transport

**Dependencies:** Tasks A-D.

**Files:**
- Create: `src/main/java/com/example/relay/deliveryengine/http/WebhookHeaders.java`
- Create: `src/main/java/com/example/relay/deliveryengine/http/WebhookHttpResponse.java`
- Create: `src/main/java/com/example/relay/deliveryengine/http/WebhookFailureCode.java`
- Create: `src/main/java/com/example/relay/deliveryengine/http/WebhookDeliveryException.java`
- Create: `src/main/java/com/example/relay/deliveryengine/http/WebhookResponseBodyConsumer.java`
- Create: `src/main/java/com/example/relay/deliveryengine/http/WebhookHttpTransport.java`
- Create: `src/main/java/com/example/relay/deliveryengine/http/ApacheWebhookHttpTransport.java`
- Create: `src/test/java/com/example/relay/deliveryengine/http/ApacheWebhookHttpTransportTest.java`
- Create: `src/test/java/com/example/relay/deliveryengine/http/ApacheWebhookHttpTransportDeadlineTest.java`

**Interfaces:**

```java
public record WebhookHeaders(String relayId, long relayTimestamp, String relaySignature) {}
public record WebhookHttpResponse(int statusCode, String responseBody) {}

public interface WebhookHttpTransport {
    WebhookHttpResponse post(String rawDestinationUrl, byte[] body, WebhookHeaders headers)
            throws WebhookDeliveryException;
}

public interface WebhookResponseBodyConsumer {
    String consume(InputStream body) throws IOException;
}

public enum WebhookFailureCode {
    DESTINATION_INVALID,
    DNS_RESOLUTION_FAILED,
    DESTINATION_POLICY_BLOCKED,
    DELIVERY_TIMEOUT,
    TRANSPORT_FAILURE
}
```

`WebhookDeliveryException` exposes `failureCode()` and `boundedDiagnostic()`; it does not expose credentials, full
untrusted URLs, or raw stack traces.

It represents only expected external destination/exchange failures: invalid persisted destinations; DNS
failure/timeout/saturation; policy rejection; TCP/connect, TLS/hostname-verification, request/response I/O, total
exchange timeout/cancellation, and bounded response-read failures. It must not be used as a catch-all wrapper for
programming defects or violated Relay invariants.

**Responsibility:** Own request construction, deadline scope, Apache execution, stable error mapping, and response
orchestration. It accepts no proxy/context/client customization from its caller.

- [ ] **Step E1: Write exact-request failing tests**

Use a local byte-capturing server and a stub body consumer. Assert:

- the exact input `byte[]` bytes arrive unchanged, including Unicode JSON fixtures;
- `Content-Type` remains compatible with plain `application/json` and does not gain an accidental charset parameter;
- relay header values exactly match `WebhookHeaders`;
- request authority remains the normalized hostname;
- a 3xx is returned without following; and
- invalid URI and expected resolver exceptions map to their stable codes.

Run and expect compilation failure:

```bash
./mvnw test -Dtest=ApacheWebhookHttpTransportTest,ApacheWebhookHttpTransportDeadlineTest
```

- [ ] **Step E2: Implement request construction without re-encoding**

Parse again with `WebhookUriParser`, open a 15-second `DeliveryDeadlineContext`, build `HttpPost` from the normalized
URI, set the three relay headers, and attach:

```java
new ByteArrayEntity(body, ContentType.create("application/json"))
```

Do not convert the body back to String and do not serialize JSON in the transport.

- [ ] **Step E3: Implement the absolute deadline**

Start the deadline before Apache pool acquisition. Derive pool/connect/response timeouts from `deadline.remaining()`.
Schedule `HttpUriRequestBase.cancel()` for the absolute deadline and cancel that scheduled task in `finally`, after the
body consumer completes. Map deadline expiry to `DELIVERY_TIMEOUT`.

Use latch-driven tests for pool-acquisition timeout, connect stall, header stall, and body-consumer stall. Assert each
finishes within one 15-second budget plus a small scheduling tolerance rather than adding component budgets.

Add same-thread lifecycle tests proving `DeliveryDeadlineContext` is absent after a successful request, DNS failure,
destination-policy failure, HTTP/transport failure, delivery timeout/cancellation, and an exception thrown while the
scope is active. After every case, execute another exchange on the same executor thread and prove it cannot observe a
stale deadline. The transport opens and clears the context solely for Apache's synchronous resolver callback.

- [ ] **Step E4: Add a TLS identity construction test**

With a recording TLS strategy or connection-operator seam, assert the socket candidate is the resolver IP while the
TLS target argument and HTTP authority remain the normalized hostname. Full certificate/SNI tests are Task G.

- [ ] **Step E5: Verify and commit**

```bash
./mvnw test -Dtest=ApacheWebhookHttpTransportTest,ApacheWebhookHttpTransportDeadlineTest,ApacheDnsSocketBindingIntegrationTest
git add src/main/java/com/example/relay/deliveryengine/http src/test/java/com/example/relay/deliveryengine/http
git commit -m "feat: add deadline-bound webhook transport"
```

Expected: PASS. The transport is not wired into `DeliveryWorker` yet.

---

### Task F: Integrate P02 response semantics

**Dependencies:** Task E's `WebhookResponseBodyConsumer`.

**Files:**
- Modify: `src/main/java/com/example/relay/deliveryengine/worker/BoundedResponseBodyCapture.java`
- Create: `src/main/java/com/example/relay/deliveryengine/http/BoundedApacheResponseBodyConsumer.java`
- Modify: `src/main/java/com/example/relay/deliveryengine/config/DeliveryHttpClientConfig.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/worker/BoundedResponseBodyCaptureTest.java`
- Create: `src/test/java/com/example/relay/deliveryengine/http/BoundedApacheResponseBodyConsumerTest.java`
- Create: `src/test/java/com/example/relay/deliveryengine/http/ApacheResponseConsumptionIntegrationTest.java`

**Produces:** A production `WebhookResponseBodyConsumer` bean that reads at most 10,241 bytes and owns Apache
EOF/abort behavior.

- [ ] **Step F1: Refactor bounded capture tests before implementation**

Change capture's contract from “capture and close” to:

```java
Capture capture(InputStream body); // Capture(String body, boolean truncated, int bytesRead)
```

Add assertions for 0, 1, 10,240, 10,241, and much larger streams. At 10,241 or larger, `bytesRead` is exactly 10,241.
The utility itself does not gracefully close the stream.

- [ ] **Step F2: Verify the refactor fails against current code**

```bash
./mvnw test -Dtest=BoundedResponseBodyCaptureTest,BoundedApacheResponseBodyConsumerTest
```

Expected: compilation failure because the new capture contract and consumer do not exist.

- [ ] **Step F3: Implement Apache EOF versus abort handling**

Before finalizing `WebhookResponseBodyConsumer` or `BoundedApacheResponseBodyConsumer`, inspect and exercise the actual
HttpClient 5.5.2 / HttpCore 5.3.6 response APIs at the transport ownership boundary. Confirm that normal EOF/reuse,
truncation/abort, and body-read failure/discard can be controlled without unsafe casts, reliance on an undocumented
concrete stream class, or exposing Apache implementation types through the generic consumer interface.

If that cannot be established, **STOP implementation and report**:

1. the exact API and runtime stream/response behavior encountered;
2. why the planned consumer abstraction cannot reliably own abort/discard;
3. the smallest alternative abstraction or ownership boundary that distinguishes EOF/reuse, truncation/abort, and
   read-failure/discard; and
4. whether the alternative changes any P02 observable behavior.

Do not improvise an unsafe cast or drain the response merely to preserve the proposed interface sketch. Revise the
internal abstraction only after review. The P02 byte, persistence, abort, and reuse invariants remain fixed.

If the checkpoint verifies that the public API reliably supplies an `EofSensorInputStream` at this ownership boundary,
`BoundedApacheResponseBodyConsumer.consume()` may use a checked type test and call `abort()` on truncation before the
outer response closes. If not truncated, capture must have reached EOF so Apache's EOF callback and ordinary response
close can release the connection. If the verified runtime boundary differs, stop and propose the smaller alternative
rather than forcing this class sketch.

If a streaming Apache body is truncated but is not abort-capable, fail closed and cancel/discard the request rather
than gracefully draining it.

- [ ] **Step F4: Prove actual network consumption is bounded**

Use a local server that counts successful response-body writes. Test fixed-length and chunked bodies much larger than
10,241 bytes. Assert:

- Relay reads only 10,241 bytes at the application stream;
- the exchange completes before the server can send the complete throttled body;
- the first connection is discarded after truncation; and
- a subsequent request succeeds on a new connection.

For a small response, send two requests and assert one accepted socket, proving EOF permits reuse.

- [ ] **Step F5: Verify and commit**

```bash
./mvnw test -Dtest=BoundedResponseBodyCaptureTest,BoundedApacheResponseBodyConsumerTest,ApacheResponseConsumptionIntegrationTest
git add src/main/java/com/example/relay/deliveryengine/worker/BoundedResponseBodyCapture.java src/main/java/com/example/relay/deliveryengine/http/BoundedApacheResponseBodyConsumer.java src/main/java/com/example/relay/deliveryengine/config/DeliveryHttpClientConfig.java src/test/java/com/example/relay/deliveryengine/worker/BoundedResponseBodyCaptureTest.java src/test/java/com/example/relay/deliveryengine/http
git commit -m "feat: preserve bounded Apache response consumption"
```

Expected: PASS.

---

### Task G: Add adversarial network and TLS tests

**Dependencies:** Tasks A-F; transport remains independently testable before worker integration.

**Files:**
- Create: `src/test/java/com/example/relay/deliveryengine/http/ControllableHostAddressLookup.java`
- Create: `src/test/java/com/example/relay/deliveryengine/http/WebhookDestinationAdversarialIntegrationTest.java`
- Create: `src/test/java/com/example/relay/deliveryengine/http/WebhookTlsIdentityIntegrationTest.java`
- Create: `src/test/resources/tls/p03-test-ca.pem`
- Create: `src/test/resources/tls/p03-webhook-test-cert.pem`
- Create: `src/test/resources/tls/p03-webhook-test-key.pem`
- Create: `src/test/resources/tls/p03-wrong-host-cert.pem`
- Create: `src/test/resources/tls/p03-wrong-host-key.pem`

**Responsibility:** Prove the actual socket destination and identity behavior under attacker-controlled timing and
answers. Test certificates are test-only and trusted through a test-only client configuration; production trust is
never weakened.

- [ ] **Step G1: Implement a stateful controllable lookup test fixture**

The fixture records invocation count and absolute hostname, returns queued address lists, and can block/release an
invocation with latches. Tests inject a policy fixture that treats selected loopback aliases as permitted solely for
transport plumbing; production policy is separately tested to block all loopback.

- [ ] **Step G2: Test actual socket target and second-resolution trap**

Resolve `rebind.test.` first to test-permitted `127.0.0.2`; configure a trap at `127.0.0.1`. If called a second time,
the lookup returns the trap. Execute one request and assert one lookup, only the permitted listener receives the
request, and the trap receives nothing.

- [ ] **Step G3: Test answer-set and time-change behavior**

Add deterministic tests for:

- mixed permitted/protected result rejects before either listener is contacted;
- two permitted candidates fail over from unavailable first to listening second;
- a protected candidate never appears later in Apache failover;
- an existing pooled socket is reused without re-resolution;
- after the server closes that socket, the replacement connection invokes resolution again;
- DNS changes from permitted on delivery one to protected before delivery two, and delivery two opens no socket.

- [ ] **Step G4: Test proxy and redirect traps**

Set Java HTTP/HTTPS proxy properties and environment-independent Apache defaults toward a trap, then assert the direct
listener receives the request and proxy trap does not. Return 301, 302, 303, 307, and 308 locations pointing at a trap;
assert no second request for every status.

- [ ] **Step G5: Test TLS trust, SNI, and hostname verification**

Create a test client with only the test CA added to normal trust. Resolve `webhook.test` to the TLS listener. Assert:

- the valid SAN certificate succeeds;
- the server's extended TLS session observes SNI `webhook.test`;
- HTTP Host is `webhook.test:<port>`;
- a certificate trusted by the same CA but lacking `webhook.test` fails hostname verification; and
- no request bytes arrive after the wrong-host handshake failure.

Do not use `NoopHostnameVerifier` or disable endpoint identification in tests.

- [ ] **Step G6: Verify and commit**

```bash
./mvnw test -Dtest=WebhookDestinationAdversarialIntegrationTest,WebhookTlsIdentityIntegrationTest,ApacheDnsSocketBindingIntegrationTest
git add src/test/java/com/example/relay/deliveryengine/http src/test/resources/tls
git commit -m "test: prove webhook destination binding"
```

Expected: PASS.

---

### Task H: Integrate the transport into DeliveryWorker

**Dependencies:** Tasks A-G. Do not switch the worker earlier.

**Files:**
- Modify: `src/main/java/com/example/relay/deliveryengine/worker/DeliveryWorker.java`
- Modify: `src/main/java/com/example/relay/deliveryengine/config/DeliveryHttpClientConfig.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerIntegrationTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerAckLifecycleTest.java`
- Modify: `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerInFlightStateTest.java`
- Modify: any worker unit test that constructs `DeliveryWorker` directly

**Responsibility:** Replace only the worker's `RestClient` dependency with `WebhookHttpTransport`, preserving body
construction/signing and existing success/failure transactions.

- [ ] **Step H1: Update worker tests first**

Construct the worker with a fake `WebhookHttpTransport`. Add cases where the fake throws each failure code and assert:

- attempts 1-5 become `FAILED_RETRYING` with an atomically created scheduled child;
- attempt 6 becomes `DEAD`;
- `last_error` begins with the stable code and remains bounded;
- the acknowledgment future completes only after failure persistence; and
- each representative expected modeled exchange failure enters the existing retry/dead path and does not leave the
  claimed row `IN_FLIGHT`.

Also make a fake transport throw an unexpected `RuntimeException`. Assert it is not normalized to
`TRANSPORT_FAILURE`, does not invoke `handleFailure`, and remains visible through the worker's exceptional completion
path. This is an exception-boundary test, not a P04 recovery test.

Run and expect compilation/constructor failures:

```bash
./mvnw test -Dtest=DeliveryWorkerAckLifecycleTest,DeliveryWorkerInFlightStateTest
```

- [ ] **Step H2: Switch the worker dependency**

Keep the current order:

```java
byte[] body = message.getBody().toString().getBytes(StandardCharsets.UTF_8);
String signature = hmacSigner.sign(relayId, timestamp, body, signingSecret);
webhookHttpTransport.post(endpoint.getUrl(), body,
        new WebhookHeaders(relayId, timestamp, signature));
```

The same `body` reference goes to signer and transport. Do not move JSON serialization into the transport.

Catch `WebhookDeliveryException`, persist `failureCode + ": " + boundedDiagnostic`, and call the existing
`handleFailure`. The transport must wrap every expected parse, resolver, policy, Apache I/O, TLS, cancellation, and body
capture failure so those modeled exchange failures cannot escape after claim.

Do not add `catch (Exception)` or `catch (RuntimeException)` around the worker or transport boundary. Null dereferences,
internal invariant violations, and unrelated programming defects are not customer endpoint failures and must not
silently consume retry budget. P03 deliberately does not guarantee recovery from arbitrary defects that strand a
claimed execution; P04 owns execution fencing, stale/stranded ownership, and related recovery correctness.

- [ ] **Step H3: Preserve response and status handling**

For 2xx, call existing `markSucceeded`. For non-2xx, call existing failure handling with bounded response diagnostics.
Do not change attempt numbers, retry tier selection, next-retry calculation, dead-letter publication, or transaction
boundaries.

- [ ] **Step H4: Verify and commit**

```bash
./mvnw test -Dtest=DeliveryWorkerAckLifecycleTest,DeliveryWorkerInFlightStateTest,DeliveryWorkerIntegrationTest,AttemptServiceMarkFailedAndCreateRetryAtomicityTest
git add src/main/java/com/example/relay/deliveryengine/worker/DeliveryWorker.java src/main/java/com/example/relay/deliveryengine/config/DeliveryHttpClientConfig.java src/test/java/com/example/relay/deliveryengine/worker
git commit -m "feat: enforce destination policy in delivery worker"
```

Expected: PASS.

---

### Task I: Run P01, P02, and lifecycle regressions

**Dependencies:** Task H.

**Files:**
- Modify only tests whose transport-specific fixture setup must change; do not weaken assertions.
- Candidate modifications:
  - `src/test/java/com/example/relay/deliveryengine/signing/HmacSignerTest.java`
  - `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerIntegrationTest.java`
  - `src/test/java/com/example/relay/deliveryengine/worker/DeliveryWorkerAckLifecycleTest.java`
  - `src/test/java/com/example/relay/deliveryengine/config/DeliveryHttpClientPinningTest.java`
  - `src/test/java/com/example/relay/deliveryengine/config/DeliveryHttpClientConnectTimeoutTest.java`

**Responsibility:** Demonstrate that P03 changes only the network boundary and preserves signed bytes, response bounds,
deadlines, acknowledgment, retry/replay identity, atomicity, and recovery.

- [ ] **Step I1: Run exact-byte and signature regressions**

```bash
./mvnw test -Dtest=HmacSignerTest,DeliveryWorkerIntegrationTest
```

Confirm the Unicode and ASCII-control fixtures recompute the signature from the body bytes actually received by the
server. Do not replace byte equality with semantic JSON equality.

- [ ] **Step I2: Run bounded-response regressions**

Within `DeliveryWorkerIntegrationTest`, retain assertions for large success, large non-2xx, chunked oversized response,
empty body, and body timeout after headers. Add an assertion that oversized Apache responses use a replacement socket
while small responses may reuse one.

- [ ] **Step I3: Run lifecycle and concurrency regressions**

```bash
./mvnw test -Dtest=DeliveryWorkerAckLifecycleTest,AttemptServiceMarkFailedAndCreateRetryAtomicityTest,DeliveryReplayConcurrencyPostgresTest,DeliveryReplayLifecycleIntegrationTest,ReconciliationSweeperIntegrationTest,ReadyWorkDispatcherIntegrationTest
```

Expected: PASS with unchanged six-attempt accounting and domain identity.

- [ ] **Step I4: Commit only necessary test fixture adaptations**

```bash
git add src/test/java/com/example/relay/deliveryengine src/test/java/com/example/relay/delivery
git commit -m "test: preserve delivery invariants through P03"
```

Skip this commit if no tracked files changed in Task I.

---

### Task J: Final P03 and repository verification

**Dependencies:** Tasks A-I.

**Files:**
- Modify: `docs/reviews/probes/README.md` only if its documented P03/P01/P02 verification commands or expected results are stale.
- Do not modify production code in this task.

**Responsibility:** Produce a clean evidence set, distinguish P03 failures from the known P00 shared-context issue, and
verify no policy bypass configuration was introduced.

- [ ] **Step J1: Run focused P03 tests**

```bash
./mvnw test -Dtest=WebhookUriParserTest,WebhookUrlValidatorTest,PublicDestinationAddressPolicyTest,SpecialPurposeAddressCatalogTest,DeliveryDnsPropertiesBindingTest,DeliveryDnsPropertiesValidationTest,DeliveryDnsPropertiesConfigurationKeysTest,DeliveryDeadlineContextTest,SystemHostAddressLookupTest,PolicyEnforcingDnsResolverTest,DeliveryHttpClientSecurityConfigTest,ApacheDnsSocketBindingIntegrationTest,ApacheWebhookHttpTransportTest,ApacheWebhookHttpTransportDeadlineTest,BoundedApacheResponseBodyConsumerTest,ApacheResponseConsumptionIntegrationTest,WebhookDestinationAdversarialIntegrationTest,WebhookTlsIdentityIntegrationTest
```

Expected: PASS.

- [ ] **Step J2: Run the post-P01/P02 targeted regression set**

```bash
./mvnw test -Dtest=HmacSignerTest,DeliveryWorkerAckLifecycleTest,DeliveryHttpClientPinningTest,DeliveryHttpClientConnectTimeoutTest,AttemptServiceMarkFailedAndCreateRetryAtomicityTest,DeliveryReplayConcurrencyPostgresTest,DeliveryReplayLifecycleIntegrationTest,PasswordResetEmailRecoverySweeperTest,PasswordResetConcurrentRequestPostgresTest,ScheduledLoopGatingTest
```

Expected: PASS unless the independently known P00 shared-Spring-context scheduler isolation issue reproduces. Do not
change scheduler beans, context caching, test ordering, or assertions to make P03 green.

- [ ] **Step J3: Run the full suite once**

```bash
./mvnw test
```

Record the exact failing test, assertion, and surrounding scheduler/context evidence for any P00 reproduction. Fix only
failures causally attributable to P03. Report unrelated failures separately and leave their code untouched.

- [ ] **Step J4: Audit forbidden configuration and dependencies**

```bash
rg -n "allow-private|NoopHostnameVerifier|TrustAll|useSystemProperties|setProxy\(|setProxySelector|SocksProxy|followRedirect|disableHostnameVerification|dnsjava" src pom.xml
```

Expected findings are only negative tests/comments documenting forbidden behavior. There must be no production bypass,
permissive TLS, proxy route, redirect following, or dnsjava dependency.

- [ ] **Step J5: Review repository diff**

```bash
git status --short
git diff --check
git diff --stat
```

Confirm no migration/schema change, Attempt state addition, retry-policy change, URL snapshot, P00 scheduler change, or
unrelated cleanup entered P03.

- [ ] **Step J6: Final implementation checkpoint**

If Task J changed the probe README, commit it separately:

```bash
git add docs/reviews/probes/README.md
git commit -m "docs: record P03 verification commands"
```

Prepare the implementation handoff with focused/full-suite results, any separately recorded P00 failure, and the final
security-invariant checklist from the design specification.
