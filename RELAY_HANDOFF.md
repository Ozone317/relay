# Relay — Context Handoff for a New Claude Session

Paste this entire document as the first message in the new session. It contains everything needed to pick up exactly where this one left off.

---

## 1. Who's building this, and how we work together

- I (the user) am learning Spring Boot from scratch. I already know Django/DRF thoroughly, so explanations that map Spring Boot concepts to Django/DRF equivalents (`@RestController` ~ ViewSet, JPA Repository interfaces ~ Django ORM manager, Bean Validation ~ serializer validators) land well.
- **Critical collaboration rule: I write all the code myself. Claude does not write production code or test code for me** — Claude explains structure, concepts, design tradeoffs, and the *why* behind a suggested approach, and I write the actual Java. This applies to both application code and test code.
- Claude's actual job in this workflow: explain concepts before I write code, review what I've written after I write it, **run the tests via Bash to verify claims rather than trusting my report or its own assumption**, read files back after I say "done" to confirm, and diagnose root causes when something fails rather than guessing at fixes.
- If Claude ever writes code directly to a file, that should only happen when I explicitly grant a one-time exception ("write it in the file this once") — the default is Claude shows code as a chat code block for me to copy in myself, or better, just explains what to write and I write it from scratch.
- We've been doing **Test-Driven Development (Red-Green-Refactor)** as the primary workflow for new features: write a failing test first (often against types/methods that don't exist yet, which is a valid "Red" — a compile failure because production code is genuinely missing), implement the minimum to pass, then refactor with tests as a safety net.

---

## 2. What Relay is

Relay started as a small Spring Boot learning project (basic `Environment`/`App` CRUD behind JWT auth) and is now deliberately evolving into the **"Relay MVP"** described in a full PRD (included in full below): a real webhook delivery platform — think a mini Svix/Hookdeck. Core hierarchy: `User → Environment → App → Endpoint`, with `Event` (a registered event type) and `Message` (a fired instance of that event with a payload) feeding a `RabbitMQ`-backed delivery engine with retries, HMAC signing, and a dead-letter view, all driven entirely through a dashboard UI (no external API-key callers yet — I'm the only user).

---

## 3. Tech stack

- Java 21, Spring Boot **3.5.16** (deliberately downgraded from 4.1.0 early on — `spring-dotenv`, the library used to load `.env` into Spring's `Environment`, targeted Boot 3.x's package layout; Boot 4.1.0 relocated `ConfigurableBootstrapContext`, silently breaking it with no error, just a no-op. 3.5.16 is also what most other third-party libraries here target.)
- Spring Security with JWT: custom `JwtService`, `JwtAuthenticationFilter`, `CustomUserDetailsService`, `AuthenticatedUser` (custom principal), `SecurityConfig`. BCrypt passwords.
- Spring Data JPA + H2 (dev/test), Postgres is the eventual target per the PRD.
- Lombok (`@NoArgsConstructor` on entities).
- Maven wrapper (`./mvnw`). **`JAVA_HOME` must be set explicitly** when running Maven commands: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw test` (the system default `java` may resolve to a different version).
- Testing: JUnit 5, Mockito, Spring's `@DataJpaTest`/`@WebMvcTest` slices, `spring-security-test` (for `MockMvc` auth simulation via `SecurityMockMvcRequestPostProcessors.authentication(...)`).
- `.env` loaded via `me.paulschwarz:spring-dotenv` (contains `JWT_SECRET` etc.).

---

## 4. Current codebase structure

```
com.example.relay
├── RelayApplication
├── common.security: JwtService, JwtAuthenticationFilter, CustomUserDetailsService, AuthenticatedUser, SecurityConfig
├── common.exception: GlobalExceptionHandler (@RestControllerAdvice), ApiError
├── user
│   ├── domain.User
│   ├── application.AuthService, api.AuthController
│   ├── api.dto.{RegisterRequest, LoginRequest, AuthResponse}
│   ├── infrastructure.UserRepository
│   └── exception.UserAlreadyExistsException
├── environment
│   ├── domain.Environment (id, name, description, user FK, createdAt, updatedAt)
│   ├── application.EnvironmentService, api.EnvironmentController
│   ├── api.dto.{EnvironmentCreateDto, EnvironmentUpdateDto, EnvironmentResponseDto}
│   ├── infrastructure.EnvironmentRepository, mapper.EnvironmentMapper
│   └── exception.EnvironmentNotFoundException
└── app
    ├── domain.App (id, name, environment FK, createdAt — IMMUTABLE, no update anywhere)
    ├── application.AppService, api.AppController
    ├── api.dto.{AppCreateDto, AppResponseDto}
    ├── infrastructure.AppRepository, mapper.AppMapper
    └── exception.AppNotFoundException
```

Routes so far:
- `POST/GET /api/v1/environments`, `GET/PATCH/DELETE /api/v1/environments/{id}`
- `POST/GET /api/v1/environments/{environmentId}/apps`, `GET/DELETE /api/v1/environments/{environmentId}/apps/{appId}` (no PATCH — Apps are immutable)
- `POST /api/v1/auth/register`, `POST /api/v1/auth/login`

**Test suite status at handoff: 59 tests, 0 failures, 0 errors**, across 14 test classes (unit + `@DataJpaTest` + `@WebMvcTest` + one full `@SpringBootTest`).

---

## 5. Key architecture decisions made (and why)

1. **Ownership/authorization is enforced by baking it into the repository query**, not by fetch-then-check in the service. E.g. `EnvironmentRepository.findByIdAndUserId(id, userId)`, `AppRepository.findByIdAndEnvironmentUserId(id, userId)` (the latter traverses `App → Environment → User` in one Spring Data derived query). Deliberate choice: a query-level check can't be accidentally skipped the way a manual `if` in a service method could.
2. **Fully nested REST routes**, e.g. `/api/v1/environments/{environmentId}/apps/{appId}`. Decided after reviewing the actual frontend design (environment selected first, then app, everything else scoped beneath) — not decided in a vacuum. `AppCreateDto` deliberately has *only* `name` — `environmentId` comes from the path, not the body (this was a mid-stream refactor; `AppCreateDto` originally carried `environmentId` in the body before the UI design settled the nesting question).
3. **`GlobalExceptionHandler` needs one `@ExceptionHandler` method per domain "NotFound" exception.** Easy to forget when adding a new entity — we forgot it for `AppNotFoundException` and only caught it via a controller test producing an uncaught-exception 500 instead of a clean 404.
4. **Every `@Service` class needs the `@Service` annotation** — forgetting it is invisible to unit tests (construct the class directly, no Spring involved) and invisible to `@WebMvcTest` (the service gets mocked away via `@MockitoBean` regardless). It **only** surfaces via the full `@SpringBootTest` (`RelayApplicationTests.contextLoads`), which is why that "trivial-looking" test earns its place. This exact bug happened with `AppService`.
5. **`App` is immutable** — no PATCH/update anywhere for it, by explicit design choice.
6. **`@WebMvcTest` + custom Spring Security requires `@Import(SecurityConfig.class)`** on the test class. `@WebMvcTest` auto-includes `Filter`-type beans (so `JwtAuthenticationFilter` gets pulled in) but not plain `@Service`/`@Component` beans it depends on (`JwtService`, `CustomUserDetailsService` need `@MockitoBean`), and does **not** auto-include your own `@Configuration` classes (`SecurityConfig`) — without `@Import`, Spring Boot falls back to default security (in-memory generated user, CSRF enabled), producing confusing 403s that look unrelated to the real cause.

---

## 6. Testing philosophy established (taught from zero JUnit/Mockito experience)

Progression covered, in order:
1. **Pure unit tests, zero mocking** (`EnvironmentMapper`) — JUnit basics, Arrange-Act-Assert, asserting against literals you control rather than re-deriving expected values from the same accessor the code under test uses (a recurring "vacuous test" trap).
2. **Single mock, branching logic** (`CustomUserDetailsService`) — `@Mock`/`@InjectMocks`/`@ExtendWith(MockitoExtension.class)`, `when/thenReturn`.
3. **Multiple mocks + `verify()`** (`EnvironmentService`, `AppService`) — proving void-method interactions happened, not just checking return values.
4. **`ArgumentCaptor`/`ArgumentMatchers.any()`/`eq()`** (`AuthService`, `AppService`) — for objects the method-under-test constructs internally (unknown values in advance, e.g. a randomly generated UUID) vs. objects the test controls directly.
5. **`ReflectionTestUtils`** (`JwtService`) — setting `@Value`-injected fields with no constructor/setter, for a class with zero Spring dependencies to mock.
6. **Static/ThreadLocal state cleanup** (`JwtAuthenticationFilter` + `SecurityContextHolder`) — `@BeforeEach`/`@AfterEach` clearing shared mutable state between tests.
7. **`@DataJpaTest` + `TestEntityManager`** (`EnvironmentRepository`, `AppRepository`, `UserRepository`) — real embedded DB per test (rolled back after), verifying derived-query correctness and DB constraints (unique email, ownership-scoping joins that traverse relationships).
8. **`@WebMvcTest` + `MockMvc` + `@MockitoBean` + `@Import(SecurityConfig.class)` + `spring-security-test`'s `authentication(auth)`** (`AuthController`, `EnvironmentController`, `AppController`) — full HTTP dispatch through the real security filter chain, with a hand-built `Authentication`/`AuthenticatedUser` principal simulating a logged-in user.
9. **Full `@SpringBootTest`** (`RelayApplicationTests.contextLoads`) — the only test that catches whole-bean-graph wiring gaps (missing `@Service`, missing `@Import`, etc.) that every other level structurally can't see.

**Recurring mistakes worth watching for when building new entities** (all were caught and fixed during this project, good to preempt):
- Confusing two different entities' UUIDs when both are in scope (e.g. using an `environmentId` where an `appId` was needed) — happened multiple times, easy copy-paste trap.
- Forgetting to actually persist fixtures in `@DataJpaTest` tests — "passes" because the DB is empty, not because the logic is correct.
- Wrong DTO/object serialized as a `MockMvc` request body when several similarly-shaped objects are in scope.
- `jsonPath(...).value(...)` needs `.toString()` for UUID/Instant fields — JSON serializes them as strings, and a raw UUID/Instant object never `.equals()` a String.
- `when(mock.voidMethod()).thenThrow(...)` doesn't compile for void methods — use `doThrow(...).when(mock).voidMethod(...)` instead.
- `thenThrow(ExceptionClass.class)` (a class literal) bypasses the exception's constructor (Mockito uses Objenesis) — prefer `thenThrow(new Exception("message"))`, a real instance, unless you specifically don't care about the message.

---

## 7. Confirmed scope decisions (already settled, don't re-litigate)

- **Replay** (future `Message`/`Attempt` feature) should work on **any** delivery attempt status, not just `DEAD` — per PRD FR-5.3, confirmed over the frontend artifact's more limited "only show Retry on DEAD" behavior.
- **DELETE should be built** for Environments/Apps/Endpoints even where the current UI draft doesn't expose it yet.
- **Endpoint URL editing (PATCH)** should be built even though the current UI draft has no edit-endpoint modal yet.
- **Whether `Event` types should be deletable is an OPEN, deliberately deferred question** — PRD's FR-3.1 only lists Environments/Applications/Endpoints for delete, and `messages.event_id` is a real FK, so this needs a real decision later (block/cascade/soft-delete), not an assumption.
- **"Sandbox"/"Getting started" auto-provisioning happens once, at account registration** — not on every login. Inferred from the frontend mock's demo-only reset behavior (its state is client-only and resets each demo run); treated as confirmed unless corrected.
- The "Simulate failures before success" field and compressed retry timers in the frontend artifact are **demo-only UI conveniences with no backend equivalent** — do not build a `failCount` column into the real `Endpoint` entity.

---

## 8. Confirmed API route map (full feature set, not all built yet)

- **Environments** (built): `POST/GET /api/v1/environments`, `GET/PATCH/DELETE /api/v1/environments/{id}`
- **Apps** (built, immutable — no PATCH): `POST/GET /api/v1/environments/{environmentId}/apps`, `GET/DELETE .../apps/{appId}`
- **Events** (not built yet — type registry, name only, scoped to an app): `POST/GET /api/v1/environments/{environmentId}/apps/{appId}/events`
- **Endpoints** (not built yet): `POST/GET .../endpoints`, `GET/PATCH/DELETE .../endpoints/{endpointId}` (PATCH = URL + active flag; returns signing secret once on create)
- **Subscriptions** (not built yet, nested under endpoint, immediate-toggle not bulk-save): `POST/DELETE .../endpoints/{endpointId}/subscriptions/{eventId}`
- **Messages** (not built yet — the "Push message" action): `POST .../apps/{appId}/messages`
- **Attempts** (not built yet — History tab): `GET .../apps/{appId}/attempts` (filterable/paginated), `GET .../attempts/{attemptId}`, `POST .../attempts/{attemptId}/replay`

---

## 9. Recommended build order going forward

1. ~~`App`~~ — **done**, full TDD vertical slice (Service → Repository → Controller).
2. **`Event`** — next up. Smallest possible entity: just a `name`, uniqueness-per-app, no delivery logic. Same TDD rhythm.
3. **`Endpoint` + `Subscription`** — secret generation, active toggle, URL editing, the subscription join. Still no delivery logic.
4. **`Message`/`Attempt` + the actual delivery engine** — its own dedicated, much larger phase: RabbitMQ topology, HMAC signing, Redis rate limiting. Matches the PRD's own Week 1 (CRUD) vs. Week 2 (delivery engine) split.

---

## 10. The PRD (full text)

```markdown
# PRD: Relay MVP – Core Delivery Engine + Manual Dashboard

**Author:** [Your Name]
**Status:** Draft v3 – added Data Model (§9) reflecting entity review; both open questions resolved (see §13)
**Relationship to the full PRD:** This is a deliberately reduced scope, not a rough draft of the same thing. Every section states what's included and links back to the full PRD (`webhook-platform-prd.md`) for anything deferred. The full PRD remains the target end-state; this document defines a buildable first milestone that gets the two things that actually matter right: **reliable delivery with retries**, and **a working UI to drive the whole system by hand.**

---

## 1. What this MVP is (and isn't)

You are the only user of this system for now. There is no separate "tenant's backend calling an API key" flow yet – you, a logged-in human, create environments, applications, endpoints, and events **through the dashboard**, and the backend does the real work: durable storage, signed HTTP delivery, retries with backoff, and a full history of what happened. The engineering that's actually hard – and actually the point – is entirely on the delivery side. Everything else in this document exists only to give you a way to drive that engine and see its output.

**In scope:**
- Environments, Applications, Endpoints – created, edited, and deleted through the dashboard.
- Manually triggering an event (via a form: pick an app, pick an event type, paste/write a JSON payload) and watching it actually get delivered to a real endpoint (e.g. a `webhook.site` URL) with retries if it fails.
- Full delivery history, dead-letter view, and manual replay – visible and usable from the UI.
- HMAC request signing and idempotency – the two properties that make this a real webhook system rather than a toy HTTP forwarder.

**Explicitly deferred to the full PRD** (do not build these now; each is a real subsystem worth doing properly later, not worth doing half-way now):
| Deferred item | Why it's cut for MVP |
|---|---|
| Tenant API keys + zero-downtime rotation (full PRD §10.1, §10.7) | Nobody but you is calling this system yet. A single login session covers 100% of the MVP's use case. |
| Kafka ingestion audit log (full PRD §5, §6) | Its entire purpose is decoupling ingestion throughput from delivery throughput at production volume. At MVP scale (you, clicking a button), fan-out can happen synchronously in the request thread with no measurable latency cost – see §5.2 below. |
| Billing / Stripe integration (full PRD §15) | Not building a business yet, building the engine. |
| Multi-user teams, roles, refresh tokens (full PRD §9, §10.6) | One user per tenant, one long-lived session, is enough to drive the UI yourself. |
| Dead-letter email alerts (full PRD FR-6.2) | The dashboard is open in front of you; a dashboard badge is enough for now. |
| SSRF hardening beyond basic IP-range checks (full PRD §10.3) | Kept as a baseline MUST (see §10) since it's cheap, but the network-level ECS security group isolation and DNS-rebinding defense-in-depth are deferred until this is deployed for anyone but you. |
| Per-endpoint custom backoff schedules, client SDKs | Pure polish, zero learning value for the core problem. |

If you build this MVP well, migrating to the full PRD later is additive (add a `tenants → environments` fixed live/test constraint, add API keys, add Kafka in front of the existing fan-out) rather than a rewrite – the core tables and the RabbitMQ retry mechanism carry over unchanged.

---

## 2. Goals

- G1: An event created through the dashboard is durably stored, then delivered to every matching endpoint, surviving endpoint downtime via automatic retries with backoff.
- G2: Every delivered request is HMAC-signed and idempotent (stable delivery ID across retries), so this is a credible demonstration of a real webhook system, not a naive HTTP forwarder.
- G3: A user can create and manage the full object hierarchy (Environment → Application → Endpoint) entirely through the UI – no direct database access or seed scripts required to use the system.
- G4: A user can see, for any event, exactly what happened: every delivery attempt, its status and response, and can manually replay it.
- G5: Failing endpoints don't get infinite retries or silently disappear – they land in a visible dead-letter state after the backoff schedule is exhausted.

## 3. Non-Goals

- NG1: No production-grade multi-tenancy, billing, or external API surface for other people's backends to call. (Full PRD scope.)
- NG2: No ordering guarantees anywhere (same reasoning as the full PRD – nothing here needs it).
- NG3: No horizontal-scale performance targets. This runs comfortably on `docker-compose up` on a laptop; there is no load-testing requirement for the MVP.

---

## 4. Actors

| Role | Who | Notes |
|---|---|---|
| **User** | You | Logs into the dashboard (email + password). Owns exactly one Tenant, created automatically at signup. Creates and manages everything through the UI. |
| **Endpoint** | A URL you register (real ones – point these at `webhook.site`, a local `ngrok` tunnel, or a small test server you write) | Receives signed, retried HTTP POSTs. This is where you verify the system is actually doing its job. |

### 4.1 Object hierarchy (simplified from the full PRD)

```
Tenant (created automatically at signup – one per user)
 └── Environment (user-created, free-form name – e.g. "sandbox", "demo")
       └── Application (user-created – e.g. "Test Customer A")
             └── Endpoint (user-created – a real URL)
                   └── Subscriptions (event types this endpoint receives)
```

Unlike the full PRD, **environments are not restricted to a fixed `live`/`test` pair** – you create as many as you want, with any name. This is a deliberate simplification: the fixed live/test invariant exists in the full PRD to support real API-key-scoped production/test isolation, which doesn't apply here since there's no external API caller to isolate from. Collapsing this to free-form, user-managed environments is strictly simpler to build and matches "you're doing everything by hand" – the fixed-pair constraint is easy to add later without touching any other table.

---

## 5. System Architecture

### 5.1 Component diagram

```
                    ┌───────────────────────────┐
  Dashboard   ────▶ │  Backend API             │  (Spring Boot, JWT-authenticated)
  (Next.js)         │  - Create event          │
                     │  - Persist Event (PG)    │
                     │  - Look up subscriptions │
                     │  - Create DeliveryAttempt│
                     │    rows (PG)             │
                     │  - Publish to RabbitMQ   │
                     └───────────┬───────────────┘
                                 │ publish, one message per matching endpoint
                                 ▼
                     ┌───────────────────────────┐
                     │  delivery.tasks queue    │  (RabbitMQ, per-message ack)
                     └───────────┬───────────────┘
                                 ▼
                     ┌───────────────────────────┐
                     │  Delivery Worker         │  (same Spring Boot app or a
                     │  - Redis token bucket    │   separate process – see §7)
                     │  - HMAC sign             │
                     │  - HTTP POST (15s        │
                     │    timeout)              │
                     │  - Update DeliveryAttempt│
                     │  - ack / nack            │
                     └───────────┬───────────────┘
                                 │ on failure: publish to delivery.wait.{tier}
                                 ▼
              ┌─────────────────────────────────────────┐
              │ delivery.wait.30s / 2m / 10m / 1h / 6h │  (TTL + Dead Letter Exchange
              │                                        │   back to delivery.tasks –
              │                                        │   identical mechanism to the
              │                                        │   full PRD, §11 there)
              └─────────────────────┬───────────────────┘
                                  │ TTL expires → auto dead-lettered back
                                  ▼
                     (loop until max attempts – delivery.deadletter queue)

  Shared state:
    - PostgreSQL: everything (tenants, users, environments, applications, endpoints,
      subscriptions, events, delivery_attempts)
    - Redis: per-endpoint token bucket rate limiting, nothing else for MVP
      (no idempotency-key cache – the Postgres unique index alone is the MVP's
      idempotency mechanism; Redis is a performance layer the full PRD adds later)
    - RabbitMQ: delivery task queue + retry timing (TTL + DLX), same design as
      the full PRD with no Kafka in front of it
```

### 5.2 Why no Kafka for the MVP

The full PRD uses Kafka exclusively to decouple ingestion throughput from delivery throughput – it exists to protect the system from a *burst* of incoming events (e.g. 50,000 events arriving in one backfill). The MVP has exactly one source of events: you, clicking "Create Event" in a browser, one at a time. There is no burst to protect against, so the entire justification for that component doesn't apply yet. Fan-out (looking up matching endpoints and creating `DeliveryAttempt` rows) happens **synchronously inside the `POST /apps/{appId}/events` request handler** – it's a handful of indexed database queries and a few RabbitMQ publishes, which completes in single-digit milliseconds even with dozens of endpoints. Adding Kafka now would mean standing up and operating a fourth stateful system to solve a scaling problem the MVP doesn't have.

### 5.3 Why RabbitMQ stays

This is the one piece carried over unchanged from the full PRD, because it's the actual point of the project: per-message ack (no head-of-line blocking) and broker-native TTL+DLX retry timing (no scheduler code to write). Simplifying this away would mean simplifying away the thing you said you actually want to get right.

---

## 6. Functional Requirements

### FR-1: Durable delivery with retries (the core requirement)
- FR-1.1: An event submitted via the dashboard MUST be persisted to Postgres before the UI shows it as created.
- FR-1.2: A failed delivery attempt (timeout, connection error, or 5xx/429 response) MUST be retried automatically per the backoff schedule in §8, with zero manual intervention required to trigger a retry.
- FR-1.3: A delivery attempt that has exhausted all retries MUST transition to a `FAILED_TERMINAL` state and appear in a dead-letter view, remaining manually replayable.
- FR-1.4: Retry state MUST survive a backend restart – since it lives in RabbitMQ (in-flight wait-queue messages) and Postgres (attempt history), restarting the Spring Boot app mid-retry-cycle must not lose or duplicate a pending retry.

### FR-2: Authenticity and idempotency
- FR-2.1: Every outbound delivery MUST carry `relay-id`, `relay-timestamp`, and `relay-signature` headers, HMAC-SHA256 signed per-endpoint (identical construction to full PRD §10.2).
- FR-2.2: `relay-id` MUST stay identical across all retries of the same delivery attempt, so a receiving test endpoint can demonstrate deduplication.
- FR-2.3: The dashboard's "Create Event" form MUST support an optional idempotency key field; submitting the same key twice for the same application within 24 hours MUST return the original event rather than creating a duplicate.

### FR-3: Manual object management via UI
- FR-3.1: A user MUST be able to create, view, and delete Environments, Applications, and Endpoints entirely through dashboard forms.
- FR-3.2: Creating an Endpoint MUST let the user specify a URL and a list of subscribed event types (free-text tags), and MUST display the generated signing secret exactly once.
- FR-3.3: A user MUST be able to edit an Endpoint's URL, subscriptions, and active/inactive flag after creation.

### FR-4: Manual event triggering
- FR-4.1: A user MUST be able to create an event via a form: select an Application, type an event type string, and provide a JSON payload (a plain textarea with client-side JSON validation before submit is sufficient).
- FR-4.2: On submission, the backend MUST immediately fan out to all matching, active endpoints under that Application and enqueue delivery tasks – the user should see delivery attempts appear within a second or two of clicking submit.

### FR-5: Delivery observability and replay
- FR-5.1: A per-endpoint delivery history table MUST show, at minimum: event type, timestamp, status, HTTP response code, attempt number, and latency.
- FR-5.2: Clicking into a delivery attempt MUST show the full request payload sent and the full response body received (truncated to a reasonable size, e.g. 10KB).
- FR-5.3: A "Replay" button MUST be available on any past event (regardless of its current status) and MUST create a fresh delivery attempt using the original stored payload.
- FR-5.4: A dedicated dead-letter view MUST list all `FAILED_TERMINAL` attempts across all endpoints, with the same replay capability.

### FR-6: Basic isolation
- FR-6.1: Each endpoint MUST have an independent rate limit (Redis token bucket) so that manually spamming "Create Event" against one broken endpoint doesn't starve delivery to a different, healthy endpoint in the same test session.

---

## 7. Tech Stack (trimmed from the full PRD)

| Layer | Choice | Change from full PRD |
|---|---|---|
| Language/runtime | Java 21, Spring Boot 3.3+ | Unchanged. |
| Web layer | Spring MVC + virtual threads for delivery workers | Unchanged – this decision didn't depend on scale, so it carries over as-is. |
| Persistence | PostgreSQL 16, Spring Data JPA, JSONB payloads | Unchanged. |
| Delivery queue & retries | RabbitMQ, TTL + DLX pattern | Unchanged – this is the piece you explicitly want to nail. |
| Durable ingestion log | ~~Kafka~~ **removed for MVP** | See §5.2. Add back when a real external ingestion API with unpredictable burst traffic exists. |
| Cache / rate limiting | Redis 7 – token bucket only | Idempotency caching and endpoint/subscription read-through caching are dropped; at MVP scale, hitting Postgres directly for these is fast enough that the cache layer adds complexity without a measurable benefit. |
| Auth | Spring Security, JWT bearer token from email/password login. **No refresh tokens** – a single access token with a 24-hour expiry, requiring a fresh login once a day. | Full PRD's access/refresh split is a legitimate security improvement (shorter-lived tokens) that matters once this has real users; for a single-developer MVP, one longer-lived token removes an entire table and an entire refresh flow for negligible practical risk. |
| Dashboard | React + Next.js SPA, calling the same backend directly (no separate `/dashboard-api` vs `/v1` split – there's only one API surface now, since there's no separate machine-caller to isolate it from) | Simplified: one API, one auth scheme. |
| Deployment | `docker-compose up` – Postgres + RabbitMQ + Redis + Spring Boot app + Next.js dev server + the local echo test server (§13.1), all local | AWS ECS/Fargate deployment, managed data stores, and CI/CD are full-PRD concerns (§6, §16 there) – not needed to prove the core engine works. |
| Testing | JUnit 5 + Testcontainers (Postgres, RabbitMQ, Redis) for the delivery engine specifically – this is the part worth testing rigorously even at MVP scope, since "does retry actually work" is the whole point | Unchanged in spirit, trimmed to cover only the delivery path rather than a full CI/CD pipeline. |

---

## 8. Retry Design (unchanged from the full PRD – this is the part you care about)

| Attempt | Delay | Implemented as |
|---|---|---|
| 1 | immediate | direct publish to `delivery.tasks` |
| 2 | 30 seconds | `delivery.wait.30s` (TTL, DLX → `delivery.tasks`) |
| 3 | 2 minutes | `delivery.wait.2m` |
| 4 | 10 minutes | `delivery.wait.10m` |
| 5 | 1 hour | `delivery.wait.1h` |
| 6 (final) | 6 hours | `delivery.wait.6h`; on failure, publish to `delivery.deadletter` |

Total window before `FAILED_TERMINAL`: ~7.5 hours – deliberately shorter than the full PRD's 72-hour window, purely so that manually testing "does the dead-letter path work" doesn't require leaving a test running overnight. This value is a single config property (`relay.retry.max-attempts`, `relay.retry.tiers`), not hardcoded, so tightening it further for a quick demo (e.g., 5s/15s/30s/1m/2m for a five-minute end-to-end test) is a config change, not a code change – worth doing when you actually demo this.

Failure classification (retryable vs. terminal) and the ±10% jitter are identical to the full PRD §11.1–11.2 – no simplification here, since getting this exactly right is the actual engineering content of the project.

---

## 9. Data Model (PostgreSQL)

This reflects the entity review above: `app_id` is denormalized onto every table below `App` because "all X within this app" is a real, frequent query (endpoints, events, messages, attempts, subscriptions all need it). `environment_id` and `user_id` are **not** denormalized past `App` – there's no query in this MVP that needs "all X in this environment" without also filtering by app, so those columns would be pure redundancy with drift risk and no benefit. When environment-scoped views are needed (e.g. a future "all activity in this environment" dashboard page), a single join through `apps` gets there – see the note after the schema.

```sql
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL,
    email           CITEXT NOT NULL UNIQUE,   -- requires the citext extension for case-insensitive uniqueness
    password_hash   TEXT NOT NULL,            -- bcrypt, cost factor 12
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE environments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id),
    name            TEXT NOT NULL,            -- free-form, user-chosen (see §4.1 – no fixed live/test pair in the MVP)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_environments_user ON environments(user_id);

CREATE TABLE apps (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    environment_id  UUID NOT NULL REFERENCES environments(id),
    name            TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_apps_environment ON apps(environment_id);

CREATE TABLE events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id          UUID NOT NULL REFERENCES apps(id),
    name            TEXT NOT NULL,            -- e.g. "payment.completed" – the type identifier
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_events_app_name ON events(app_id, name);   -- enforces "unique per app"

CREATE TABLE endpoints (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id              UUID NOT NULL REFERENCES apps(id),
    name                TEXT NOT NULL,
    url                 TEXT NOT NULL,
    signing_secret      TEXT NOT NULL,        -- generated at creation, shown once, encrypted at rest
    is_active           BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_endpoints_app ON endpoints(app_id);

CREATE TABLE subscriptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id          UUID NOT NULL REFERENCES apps(id),
    event_id        UUID NOT NULL REFERENCES events(id),
    endpoint_id     UUID NOT NULL REFERENCES endpoints(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_id, endpoint_id)            -- an endpoint can't subscribe to the same event type twice
);
CREATE INDEX idx_subscriptions_app ON subscriptions(app_id);
CREATE INDEX idx_subscriptions_endpoint ON subscriptions(endpoint_id);

CREATE TABLE messages (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id              UUID NOT NULL REFERENCES apps(id),
    event_id            UUID NOT NULL REFERENCES events(id),   -- FK, not a copied name string – see design note below
    body                JSONB NOT NULL,
    idempotency_key     TEXT,                  -- nullable; from the optional dashboard field, FR-2.3
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_messages_app ON messages(app_id);
CREATE INDEX idx_messages_event ON messages(event_id);
CREATE UNIQUE INDEX idx_messages_idempotency ON messages(app_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE attempts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id              UUID NOT NULL REFERENCES apps(id),
    message_id          UUID NOT NULL REFERENCES messages(id),
    endpoint_id         UUID NOT NULL REFERENCES endpoints(id),
    attempt_no          INT NOT NULL DEFAULT 0,
    status              TEXT NOT NULL CHECK (status IN
                            ('CREATED','IN_FLIGHT','SUCCEEDED','FAILED_RETRYING','DEAD')),
    next_retry_at       TIMESTAMPTZ,           -- denormalized display value only – RabbitMQ's TTL+DLX
                                                -- owns actual retry timing (§8); this column exists purely
                                                -- so the dashboard can show "next retry at ~14:32"
    response_code       INT,
    response_body       TEXT,                  -- truncated to 10KB at write time
    last_error          TEXT,                  -- populated when there's no response at all: timeout, DNS
                                                -- failure, connection refused – response_code/body stay NULL
    latency_ms          INT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_attempts_app ON attempts(app_id);
CREATE INDEX idx_attempts_message ON attempts(message_id);
CREATE INDEX idx_attempts_endpoint ON attempts(endpoint_id, created_at DESC);
CREATE INDEX idx_attempts_retry_due ON attempts(next_retry_at) WHERE status = 'FAILED_RETRYING';
```

**Design notes, tying back to the entity review:**
- `messages.event_id` is a foreign key to `events.id`, not a copied `event_name` string – this is the fix from the earlier review: if an event type were ever renamed, every historical message stays correctly linked instead of silently carrying a stale string with no way back to the current `Event` row.
- `attempts.status` includes `SUCCEEDED` as its own terminal state, distinct from `DEAD` – this is what lets a worker or the dashboard tell "delivered" apart from "still retrying" apart from "gave up," which the original four-state list (`CREATED | IN-FLIGHT | FAILED | DEAD`) couldn't express.
- `endpoints.signing_secret` and `endpoints.is_active` are both new versus the original diagram – the former is required for FR-2.1 (HMAC signing), the latter for FR-3.3 (toggle without delete).
- Every table has `created_at`; `attempts` additionally has `updated_at` since a single attempt row is mutated in place as it moves through retry states, and you'll want to know when it last changed, not just when it was first created.
- **No `user_id` or `environment_id` below `App`.** If you later need "all messages in environment X" for a dashboard view, join through `apps`:
  ```sql
  SELECT m.* FROM messages m JOIN apps a ON m.app_id = a.id WHERE a.environment_id = ?
  ```
  This is one indexed hop and can't drift out of sync the way a denormalized `environment_id` column could – there's nowhere for it to disagree with the truth, since it's derived at query time rather than copied at write time.

---

## 10. Security (baseline only)

- **Passwords:** bcrypt, cost factor 12. Same reasoning as the full PRD §10.6 – human passwords are low-entropy, bcrypt's cost is the correct defense.
- **JWT:** HMAC-SHA256 signed, 24-hour expiry, `tenantId` claim used to scope every query – never trust a client-supplied tenant/environment ID.
- **HMAC webhook signing:** identical construction to the full PRD §10.2 (`{id}.{timestamp}.{body}`, HMAC-SHA256, `v1,` prefix) – this is not simplified, since it's one of the two things (along with retries) that makes this a credible webhook system rather than a toy.
- **SSRF baseline:** before sending any delivery, resolve the endpoint URL's hostname and reject private/loopback/link-local IP ranges (same list as full PRD §10.3). This is a ~20-line check and there's no good reason to skip it even at MVP scale – the full defense-in-depth (network-level isolation, redirect blocking, re-resolution at delivery time) is deferred, but the basic check is cheap enough to include now.
- **Transport:** TLS is not required for `localhost`/`docker-compose` development. Note this explicitly as a gap to close before this is ever exposed to the public internet – not a concern while it's running on your machine.

---

## 11. Dashboard Requirements

Concretely, the UI needs these screens – nothing more for MVP:

1. **Login / Signup** – email + password.
2. **Environment list + create form** – name only.
3. **Application list (within an environment) + create form** – name, optional external ID.
4. **Endpoint list (within an application) + create/edit form** – URL, description, subscribed event types (tag input), active toggle. Shows the signing secret once on creation, with a copy-to-clipboard button and a clear "you won't see this again" warning.
5. **Create Event form** – application selector, event type text field, JSON payload textarea (with a "format JSON" button and basic validation before allowing submit), optional idempotency key field.
6. **Delivery history table** – filterable by endpoint and status, showing the columns in FR-5.1, with a detail drawer/modal per row (FR-5.2) and a Replay button (FR-5.3).
7. **Dead-letter view** – same shape as the delivery history table, filtered to `FAILED_TERMINAL`, also with Replay.

No design polish requirement here – a clean, functional table-and-form UI (shadcn/ui components are fine, per the frontend tooling already available) is the bar. Visual polish is a full-PRD/launch concern, not an MVP one.

---

## 12. Roadmap

**Week 1 – Data model + object CRUD**
Postgres schema exactly as in §9 (users, environments, apps, events, endpoints, subscriptions – no messages/attempts yet), JWT auth, and the corresponding CRUD API + basic dashboard forms for environments/apps/endpoints. Success criterion: you can log in and build out an environment → app → endpoint tree entirely through the UI.

**Week 2 – The actual delivery engine**
Event creation endpoint, fan-out logic, RabbitMQ topology (`delivery.tasks` + the five wait queues + DLQ), delivery worker with HMAC signing and virtual-thread HTTP calls, `delivery_attempts` tracking. **Also build the local echo test server this week (see §13.1)** – do it alongside the delivery engine, not after, since you'll want it the moment you're testing retries at all. Success criterion: create an event through a raw API call (Postman is fine before the UI form exists), point it at the echo server, configure it to fail twice then succeed, and watch the exact retry schedule from §8 play out against your own logs – not against a third party's uptime.

**Week 3 – Dashboard for events and history**
Create Event form, delivery history table with filters and detail view, manual replay, dead-letter view. Success criterion: the entire loop – create endpoint, create event, watch it fail, watch it retry, watch it land in dead-letter, click replay, watch it succeed – is doable without touching a terminal or a database client.

**Week 4 – Rate limiting + polish + tests**
Redis token bucket (FR-6.1), Testcontainers-based integration tests for the retry engine specifically (this is the highest-value test suite in the whole project – write tests that configure the echo server from §13.1 to fail N times before succeeding, then assert the exact retry timing against it, fully self-contained with no external network dependency), README with architecture diagram and a recorded demo GIF/video for your portfolio.

At the end of week 4 you have something demoable, testable, and honestly explainable in an interview – and every piece of it (RabbitMQ topology, HMAC signing, idempotency, virtual threads) upgrades cleanly into the full PRD later without a rewrite.

---

## 13. Resolved Decisions

Both items previously listed as open questions are now settled – decisions and the resulting concrete spec below.

### 13.1 Local echo test server – build it (resolves former OQ1)

A small, purpose-built HTTP server, run as its own service in `docker-compose.yml`, exists solely so retry testing is deterministic and doesn't depend on a third party (`webhook.site`) being reachable or its stored-request history being available when you check back.

**Behavior:**
- A single route, `POST /echo/:scenarioId`, accepts any JSON body.
- Before responding, it looks up `scenarioId` in an in-memory (or Redis-backed, if you want it to survive the echo server's own restarts) counter.
- Configuration per scenario: `failCount` (how many times to fail before succeeding) and `failMode` (`500`, `timeout`, or `connection_reset`).
- Each request increments the counter for that `scenarioId`. While the counter is `<= failCount`, it responds according to `failMode` (e.g., HTTP 500, or hangs past the 15-second client timeout, or drops the connection). Once the counter exceeds `failCount`, it responds `200 OK` and logs the full received request – headers included, so you can visually confirm `relay-signature` and `relay-id` are present and correctly formed.
- A second route, `GET /echo/:scenarioId/log`, returns the full list of requests received for that scenario (timestamp, headers, body) as JSON – this is what both your manual dashboard-driven testing and your Week 4 automated tests read to assert against.
- A third route, `DELETE /echo/:scenarioId`, resets a scenario's counter and log – needed between test runs.

**Implementation note:** this can be a genuinely tiny standalone service – 40-60 lines in plain Express (Node) or a single-file Spring Boot `@RestController`, whichever you'd rather not spend real time on. Given the goal is testing *your* Spring Boot delivery engine, writing the echo server in Node is arguably the better choice specifically so it reads unambiguously as a test fixture and not as part of the system under test.

**How this plugs into Week 4's tests:** a Testcontainers-based integration test creates an endpoint pointed at `http://echo:8080/echo/test-run-1`, configures that scenario with `failCount=2, failMode=500`, creates an event, and then asserts against `GET /echo/test-run-1/log` that exactly 3 requests arrived, with timestamps matching the §8 backoff schedule within the jitter tolerance, and that the 3rd request's `relay-id` header is identical to the first two.

### 13.2 24-hour single JWT, no refresh token – accepted as-is (resolves former OQ2)

Confirmed as the MVP's auth model: one access token, 24-hour expiry, re-login when it lapses. This is documented in §7's tech stack table and restated here only to close the loop – it's a stated, deliberate tradeoff for a single-developer MVP, not an oversight, and the full PRD's access/refresh split (§10.6 there) is the correct fix once this has real users other than you. No further action needed; carry this line into the README's "known simplifications" section mentioned in Week 4.
```

---

## 11. The frontend design artifact (full React code)

This is a client-only mock (no real API calls, all state is local React state) that establishes the intended UI flow, screen layout, and navigation hierarchy. It informed the nested-routing decision in §5 above.

```jsx
import React, { useState } from 'react';
import {
  Plus, X, Check, Copy, RefreshCw, Send, ChevronDown, Clock,
  CheckCircle2, XCircle, Loader2, AlertCircle, LogOut, Layers,
  Boxes, Radio, ListChecks, Activity, ExternalLink
} from 'lucide-react';

let idSeq = 0;
const uid = (prefix) => `${prefix}_${(++idSeq).toString(36)}${Math.random().toString(36).slice(2, 6)}`;

const RETRY_TIERS = [
  { attemptNo: 2, label: '30s', delayMs: 1400 },
  { attemptNo: 3, label: '2m', delayMs: 2000 },
  { attemptNo: 4, label: '10m', delayMs: 2600 },
  { attemptNo: 5, label: '1h', delayMs: 3200 },
  { attemptNo: 6, label: '6h', delayMs: 3800 },
];
const MAX_ATTEMPTS = 6;

function genSecret() {
  const chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
  let s = '';
  for (let i = 0; i < 32; i++) s += chars[Math.floor(Math.random() * chars.length)];
  return `whsec_${s}`;
}

function timeAgo(date) {
  const s = Math.floor((Date.now() - date.getTime()) / 1000);
  if (s < 5) return 'just now';
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  return `${h}h ago`;
}

function StatusBadge({ status }) {
  const map = {
    IN_FLIGHT: { cls: 'bg-amber-50 text-amber-700 border-amber-200', icon: <Loader2 size={12} className="animate-spin" />, label: 'In flight' },
    SUCCEEDED: { cls: 'bg-emerald-50 text-emerald-700 border-emerald-200', icon: <CheckCircle2 size={12} />, label: 'Succeeded' },
    FAILED_RETRYING: { cls: 'bg-amber-50 text-amber-700 border-amber-200', icon: <RefreshCw size={12} />, label: 'Retrying' },
    DEAD: { cls: 'bg-rose-50 text-rose-700 border-rose-200', icon: <XCircle size={12} />, label: 'Dead' },
  };
  const m = map[status] || map.IN_FLIGHT;
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full border text-xs font-medium ${m.cls}`}>
      {m.icon}{m.label}
    </span>
  );
}

function Modal({ title, onClose, children, wide }) {
  return (
    <div className="fixed inset-0 bg-slate-900/40 flex items-center justify-center z-50 p-4">
      <div className={`bg-white rounded-xl border border-slate-200 shadow-xl w-full ${wide ? 'max-w-lg' : 'max-w-sm'} max-h-[85vh] overflow-y-auto`}>
        <div className="flex items-center justify-between px-5 py-3.5 border-b border-slate-100">
          <h3 className="text-sm font-semibold text-slate-800">{title}</h3>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600">
            <X size={18} />
          </button>
        </div>
        <div className="p-5">{children}</div>
      </div>
    </div>
  );
}

function Field({ label, children }) {
  return (
    <div className="mb-3.5">
      <label className="block text-xs font-medium text-slate-600 mb-1.5">{label}</label>
      {children}
    </div>
  );
}

const inputCls = 'w-full px-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500/30 focus:border-indigo-400 bg-white';
const btnPrimary = 'inline-flex items-center gap-1.5 px-3.5 py-2 text-sm font-medium text-white bg-indigo-600 hover:bg-indigo-700 rounded-lg transition-colors';
const btnSecondary = 'inline-flex items-center gap-1.5 px-3.5 py-2 text-sm font-medium text-slate-700 bg-white border border-slate-200 hover:bg-slate-50 rounded-lg transition-colors';
const btnGhost = 'inline-flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium text-slate-600 hover:bg-slate-100 rounded-md transition-colors';

export default function RelayDashboard() {
  const [screen, setScreen] = useState('login');
  const [loginEmail, setLoginEmail] = useState('');
  const [loginPassword, setLoginPassword] = useState('');
  const [loginError, setLoginError] = useState('');
  const [showWelcome, setShowWelcome] = useState(false);

  const [environments, setEnvironments] = useState([]);
  const [apps, setApps] = useState([]);
  const [events, setEvents] = useState([]);
  const [endpoints, setEndpoints] = useState([]);
  const [subscriptions, setSubscriptions] = useState([]);
  const [messages, setMessages] = useState([]);
  const [attempts, setAttempts] = useState([]);

  const [currentEnvId, setCurrentEnvId] = useState(null);
  const [currentAppId, setCurrentAppId] = useState(null);
  const [activeTab, setActiveTab] = useState('events');

  const [showNewEnvModal, setShowNewEnvModal] = useState(false);
  const [newEnvName, setNewEnvName] = useState('');
  const [showNewAppModal, setShowNewAppModal] = useState(false);
  const [newAppName, setNewAppName] = useState('');
  const [showNewEventModal, setShowNewEventModal] = useState(false);
  const [newEventName, setNewEventName] = useState('');
  const [eventError, setEventError] = useState('');
  const [showNewEndpointModal, setShowNewEndpointModal] = useState(false);
  const [newEpName, setNewEpName] = useState('');
  const [newEpUrl, setNewEpUrl] = useState('');
  const [newEpFailCount, setNewEpFailCount] = useState(0);
  const [revealSecret, setRevealSecret] = useState(null);
  const [subsPanelEndpoint, setSubsPanelEndpoint] = useState(null);

  const [pushEventId, setPushEventId] = useState('');
  const [pushPayload, setPushPayload] = useState('{\n  "amount": 4999,\n  "currency": "usd"\n}');
  const [pushIdemKey, setPushIdemKey] = useState('');
  const [pushError, setPushError] = useState('');
  const [lastSent, setLastSent] = useState(null);

  const [historyPage, setHistoryPage] = useState(1);
  const [historyEndpointFilter, setHistoryEndpointFilter] = useState('all');
  const [historyStatusFilter, setHistoryStatusFilter] = useState('all');
  const PAGE_SIZE = 6;

  function handleLogin() {
    if (!loginEmail.trim() || !loginPassword.trim()) {
      setLoginError('Enter an email and password.');
      return;
    }
    setLoginError('');
    const envId = uid('env');
    const appId = uid('app');
    setEnvironments([{ id: envId, name: 'Sandbox', createdAt: new Date() }]);
    setApps([{ id: appId, envId, name: 'Getting started', createdAt: new Date() }]);
    setCurrentEnvId(envId);
    setCurrentAppId(appId);
    setShowWelcome(true);
    setScreen('workspace');
  }

  function handleLogout() {
    setScreen('login');
    setLoginEmail(''); setLoginPassword('');
    setEnvironments([]); setApps([]); setEvents([]); setEndpoints([]);
    setSubscriptions([]); setMessages([]); setAttempts([]);
    setCurrentEnvId(null); setCurrentAppId(null);
  }

  function handleEnvSelect(e) {
    const val = e.target.value;
    if (val === '__new__') { setShowNewEnvModal(true); return; }
    setCurrentEnvId(val);
    const appsInEnv = apps.filter(a => a.envId === val);
    setCurrentAppId(appsInEnv.length ? appsInEnv[0].id : null);
  }

  function handleAppSelect(e) {
    const val = e.target.value;
    if (val === '__new__') { setShowNewAppModal(true); return; }
    setCurrentAppId(val);
  }

  function createEnvironment() {
    if (!newEnvName.trim()) return;
    const id = uid('env');
    setEnvironments(prev => [...prev, { id, name: newEnvName.trim(), createdAt: new Date() }]);
    setCurrentEnvId(id);
    setCurrentAppId(null);
    setNewEnvName('');
    setShowNewEnvModal(false);
  }

  function createApp() {
    if (!newAppName.trim()) return;
    const id = uid('app');
    setApps(prev => [...prev, { id, envId: currentEnvId, name: newAppName.trim(), createdAt: new Date() }]);
    setCurrentAppId(id);
    setNewAppName('');
    setShowNewAppModal(false);
  }

  function createEvent() {
    const name = newEventName.trim();
    if (!name) return;
    const dup = events.some(ev => ev.appId === currentAppId && ev.name.toLowerCase() === name.toLowerCase());
    if (dup) { setEventError('An event with this name already exists in this app.'); return; }
    setEvents(prev => [...prev, { id: uid('evt'), appId: currentAppId, name, createdAt: new Date() }]);
    setNewEventName(''); setEventError(''); setShowNewEventModal(false);
  }

  function createEndpoint() {
    if (!newEpName.trim() || !newEpUrl.trim()) return;
    const id = uid('ep');
    const secret = genSecret();
    setEndpoints(prev => [...prev, {
      id, appId: currentAppId, name: newEpName.trim(), url: newEpUrl.trim(),
      active: true, failCount: Number(newEpFailCount) || 0, secret, createdAt: new Date(),
    }]);
    setNewEpName(''); setNewEpUrl(''); setNewEpFailCount(0);
    setShowNewEndpointModal(false);
    setRevealSecret({ name: newEpName.trim(), secret });
  }

  function toggleEndpointActive(id) {
    setEndpoints(prev => prev.map(e => e.id === id ? { ...e, active: !e.active } : e));
  }

  function toggleSubscription(endpointId, eventId) {
    setSubscriptions(prev => {
      const exists = prev.some(s => s.endpointId === endpointId && s.eventId === eventId);
      if (exists) return prev.filter(s => !(s.endpointId === endpointId && s.eventId === eventId));
      return [...prev, { id: uid('sub'), endpointId, eventId }];
    });
  }

  function runAttempt({ appId, messageId, endpoint, eventName, attemptNo }) {
    const attemptId = uid('att');
    setAttempts(prev => [...prev, {
      id: attemptId, appId, messageId, endpointId: endpoint.id, endpointName: endpoint.name,
      eventName, attemptNo, status: 'IN_FLIGHT', responseCode: null, latencyMs: null, createdAt: new Date(),
    }]);
    setTimeout(() => {
      const succeeds = attemptNo > endpoint.failCount;
      const latencyMs = 60 + Math.floor(Math.random() * 300);
      if (succeeds) {
        setAttempts(prev => prev.map(a => a.id === attemptId ? { ...a, status: 'SUCCEEDED', responseCode: 200, latencyMs } : a));
      } else {
        const isFinal = attemptNo >= MAX_ATTEMPTS;
        setAttempts(prev => prev.map(a => a.id === attemptId ? {
          ...a, status: isFinal ? 'DEAD' : 'FAILED_RETRYING',
          responseCode: 503, latencyMs,
        } : a));
        if (!isFinal) {
          const tier = RETRY_TIERS.find(t => t.attemptNo === attemptNo + 1);
          setTimeout(() => runAttempt({ appId, messageId, endpoint, eventName, attemptNo: attemptNo + 1 }), tier.delayMs);
        }
      }
    }, attemptNo === 1 ? 650 : 500);
  }

  function handleSendMessage() {
    let parsed;
    try { parsed = JSON.parse(pushPayload); } catch (err) { setPushError('Payload is not valid JSON.'); return; }
    if (!pushEventId) { setPushError('Choose an event type.'); return; }
    const event = events.find(ev => ev.id === pushEventId);
    const targetIds = subscriptions.filter(s => s.eventId === pushEventId).map(s => s.endpointId);
    const targets = endpoints.filter(e => targetIds.includes(e.id) && e.active && e.appId === currentAppId);
    if (targets.length === 0) { setPushError('No active endpoints are subscribed to this event.'); return; }
    setPushError('');
    const messageId = uid('msg');
    setMessages(prev => [{ id: messageId, appId: currentAppId, eventId: event.id, eventName: event.name, payload: parsed, idempotencyKey: pushIdemKey || null, createdAt: new Date() }, ...prev]);
    targets.forEach(endpoint => runAttempt({ appId: currentAppId, messageId, endpoint, eventName: event.name, attemptNo: 1 }));
    setLastSent({ eventName: event.name, endpointNames: targets.map(t => t.name), messageId, at: new Date() });
  }

  function handleReplay(attempt) {
    const endpoint = endpoints.find(e => e.id === attempt.endpointId);
    if (!endpoint) return;
    runAttempt({ appId: attempt.appId, messageId: attempt.messageId, endpoint, eventName: attempt.eventName, attemptNo: 1 });
  }

  const currentApps = apps.filter(a => a.envId === currentEnvId);
  const appEvents = events.filter(e => e.appId === currentAppId);
  const appEndpoints = endpoints.filter(e => e.appId === currentAppId);
  const appAttempts = attempts.filter(a => a.appId === currentAppId)
    .sort((a, b) => b.createdAt - a.createdAt);

  const filteredAttempts = appAttempts.filter(a =>
    (historyEndpointFilter === 'all' || a.endpointId === historyEndpointFilter) &&
    (historyStatusFilter === 'all' || a.status === historyStatusFilter)
  );
  const totalPages = Math.max(1, Math.ceil(filteredAttempts.length / PAGE_SIZE));
  const pageAttempts = filteredAttempts.slice((historyPage - 1) * PAGE_SIZE, historyPage * PAGE_SIZE);

  function subsFor(endpointId) {
    return subscriptions.filter(s => s.endpointId === endpointId).map(s => events.find(e => e.id === s.eventId)).filter(Boolean);
  }

  // ---------- LOGIN SCREEN ----------
  if (screen === 'login') {
    return (
      <div className="min-h-[520px] flex items-center justify-center bg-slate-50 p-6">
        <div className="w-full max-w-sm bg-white border border-slate-200 rounded-xl shadow-sm p-6">
          <div className="flex items-center gap-2 mb-1">
            <div className="w-7 h-7 rounded-lg bg-indigo-600 flex items-center justify-center">
              <Radio size={15} className="text-white" />
            </div>
            <span className="font-semibold text-slate-800">Relay</span>
          </div>
          <p className="text-sm text-slate-500 mb-5">Log in to manage your webhook delivery.</p>
          <div>
            <Field label="Email">
              <input type="email" className={inputCls} placeholder="name@company.com" value={loginEmail} onChange={e => setLoginEmail(e.target.value)} />
            </Field>
            <Field label="Password">
              <input
                type="password"
                className={inputCls}
                placeholder="Enter your password"
                value={loginPassword}
                onChange={e => setLoginPassword(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter') handleLogin(); }}
              />
            </Field>
            {loginError && <p className="text-xs text-rose-600 mb-3 flex items-center gap-1"><AlertCircle size={13} />{loginError}</p>}
            <button type="button" onClick={handleLogin} className={`${btnPrimary} w-full justify-center mt-1`}>Log in</button>
          </div>
          <p className="text-xs text-slate-400 mt-4 text-center">First time here? Logging in creates your sandbox environment and first app automatically.</p>
        </div>
      </div>
    );
  }

  // ---------- MAIN WORKSPACE ----------
  return (
    <div className="min-h-[600px] bg-slate-50">
      <header className="bg-white border-b border-slate-200 px-5 py-3">
        <div className="flex items-center justify-between flex-wrap gap-3">
          <div className="flex items-center gap-2">
            <div className="w-6 h-6 rounded-md bg-indigo-600 flex items-center justify-center">
              <Radio size={13} className="text-white" />
            </div>
            <span className="font-semibold text-slate-800 text-sm mr-2">Relay</span>

            <div className="flex items-center gap-1.5 text-slate-400"><Layers size={13} /></div>
            <select value={currentEnvId || ''} onChange={handleEnvSelect} className="text-sm border border-slate-200 rounded-lg px-2.5 py-1.5 bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500/30">
              {environments.map(env => <option key={env.id} value={env.id}>{env.name}</option>)}
              <option value="__new__">+ New environment</option>
            </select>

            <div className="flex items-center gap-1.5 text-slate-400 ml-1"><Boxes size={13} /></div>
            <select value={currentAppId || ''} onChange={handleAppSelect} className="text-sm border border-slate-200 rounded-lg px-2.5 py-1.5 bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500/30">
              {currentApps.length === 0 && <option value="">No apps yet</option>}
              {currentApps.map(app => <option key={app.id} value={app.id}>{app.name}</option>)}
              <option value="__new__">+ New app</option>
            </select>
          </div>
          <button onClick={handleLogout} className={btnGhost}><LogOut size={14} />Log out</button>
        </div>
      </header>

      {showWelcome && (
        <div className="bg-indigo-50 border-b border-indigo-100 px-5 py-2.5 flex items-center justify-between">
          <p className="text-xs text-indigo-800">We created a <strong className="font-medium">Sandbox</strong> environment and a <strong className="font-medium">Getting started</strong> app so you can start right away.</p>
          <button onClick={() => setShowWelcome(false)} className="text-indigo-400 hover:text-indigo-600"><X size={14} /></button>
        </div>
      )}

      <div className="p-5 max-w-4xl mx-auto">
        {!currentAppId ? (
          <div className="bg-white border border-dashed border-slate-300 rounded-xl p-10 text-center">
            <Boxes size={28} className="mx-auto text-slate-300 mb-3" />
            <p className="text-sm font-medium text-slate-700 mb-1">This environment doesn't have any apps yet</p>
            <p className="text-xs text-slate-500 mb-4">Create an app to start adding events and endpoints.</p>
            <button onClick={() => setShowNewAppModal(true)} className={btnPrimary}><Plus size={15} />Create app</button>
          </div>
        ) : (
          <>
            <div className="flex gap-1 mb-4 border-b border-slate-200">
              {[
                { id: 'events', label: 'Events', icon: <ListChecks size={14} /> },
                { id: 'endpoints', label: 'Endpoints', icon: <Radio size={14} /> },
                { id: 'push', label: 'Push message', icon: <Send size={14} /> },
                { id: 'history', label: 'History', icon: <Activity size={14} /> },
              ].map(tab => (
                <button key={tab.id} onClick={() => setActiveTab(tab.id)}
                  className={`flex items-center gap-1.5 px-3.5 py-2 text-sm font-medium border-b-2 -mb-px transition-colors ${activeTab === tab.id ? 'border-indigo-600 text-indigo-700' : 'border-transparent text-slate-500 hover:text-slate-700'}`}>
                  {tab.icon}{tab.label}
                </button>
              ))}
            </div>

            {/* EVENTS TAB */}
            {activeTab === 'events' && (
              <div className="bg-white border border-slate-200 rounded-xl">
                <div className="flex items-center justify-between px-4 py-3 border-b border-slate-100">
                  <p className="text-sm font-medium text-slate-700">Event types</p>
                  <button onClick={() => setShowNewEventModal(true)} className={btnSecondary}><Plus size={14} />New event</button>
                </div>
                {appEvents.length === 0 ? (
                  <div className="p-8 text-center text-sm text-slate-400">No event types yet. Create one to start subscribing endpoints.</div>
                ) : (
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="text-left text-xs text-slate-400 border-b border-slate-100">
                        <th className="px-4 py-2 font-medium">Name</th>
                        <th className="px-4 py-2 font-medium">Subscribers</th>
                        <th className="px-4 py-2 font-medium">Created</th>
                      </tr>
                    </thead>
                    <tbody>
                      {appEvents.map(ev => (
                        <tr key={ev.id} className="border-b border-slate-50 last:border-0">
                          <td className="px-4 py-2.5 font-mono text-xs text-slate-700">{ev.name}</td>
                          <td className="px-4 py-2.5 text-slate-500">{subscriptions.filter(s => s.eventId === ev.id).length} endpoint(s)</td>
                          <td className="px-4 py-2.5 text-slate-400 text-xs">{timeAgo(ev.createdAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            )}

            {/* ENDPOINTS TAB */}
            {activeTab === 'endpoints' && (
              <div className="bg-white border border-slate-200 rounded-xl">
                <div className="flex items-center justify-between px-4 py-3 border-b border-slate-100">
                  <p className="text-sm font-medium text-slate-700">Endpoints</p>
                  <button onClick={() => setShowNewEndpointModal(true)} className={btnSecondary}><Plus size={14} />New endpoint</button>
                </div>
                {appEndpoints.length === 0 ? (
                  <div className="p-8 text-center text-sm text-slate-400">No endpoints yet. Add one to start receiving deliveries.</div>
                ) : (
                  <div className="divide-y divide-slate-50">
                    {appEndpoints.map(ep => (
                      <div key={ep.id} className="px-4 py-3">
                        <div className="flex items-start justify-between gap-3 flex-wrap">
                          <div className="min-w-0">
                            <div className="flex items-center gap-2">
                              <span className="text-sm font-medium text-slate-800">{ep.name}</span>
                              <span className={`text-xs px-1.5 py-0.5 rounded-full border ${ep.active ? 'bg-emerald-50 text-emerald-700 border-emerald-200' : 'bg-slate-50 text-slate-500 border-slate-200'}`}>
                                {ep.active ? 'Active' : 'Inactive'}
                              </span>
                            </div>
                            <p className="font-mono text-xs text-slate-500 truncate">{ep.url}</p>
                            <div className="flex flex-wrap gap-1 mt-1.5">
                              {subsFor(ep.id).length === 0 ? (
                                <span className="text-xs text-slate-400">Not subscribed to anything yet</span>
                              ) : subsFor(ep.id).map(ev => (
                                <span key={ev.id} className="font-mono text-xs px-1.5 py-0.5 rounded bg-indigo-50 text-indigo-700">{ev.name}</span>
                              ))}
                            </div>
                          </div>
                          <div className="flex items-center gap-1.5 shrink-0">
                            <button onClick={() => setSubsPanelEndpoint(ep.id)} className={btnGhost}><ListChecks size={13} />Subscriptions</button>
                            <button onClick={() => toggleEndpointActive(ep.id)} className={btnGhost}>{ep.active ? 'Deactivate' : 'Activate'}</button>
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}

            {/* PUSH MESSAGE TAB */}
            {activeTab === 'push' && (
              <div className="bg-white border border-slate-200 rounded-xl p-5">
                <p className="text-sm font-medium text-slate-700 mb-3">Send a test message</p>
                <Field label="Event type">
                  <select value={pushEventId} onChange={e => setPushEventId(e.target.value)} className={inputCls}>
                    <option value="">Choose an event...</option>
                    {appEvents.map(ev => <option key={ev.id} value={ev.id}>{ev.name}</option>)}
                  </select>
                </Field>
                <Field label="Payload (JSON)">
                  <textarea rows={6} className={`${inputCls} font-mono text-xs`} value={pushPayload} onChange={e => setPushPayload(e.target.value)} />
                </Field>
                <Field label="Idempotency key (optional)">
                  <input className={inputCls} placeholder="e.g. order-8821-retry-1" value={pushIdemKey} onChange={e => setPushIdemKey(e.target.value)} />
                </Field>
                {pushError && <p className="text-xs text-rose-600 mb-3 flex items-center gap-1"><AlertCircle size={13} />{pushError}</p>}
                <button onClick={handleSendMessage} className={btnPrimary}><Send size={14} />Send message</button>

                {lastSent && (
                  <div className="mt-5 pt-4 border-t border-slate-100">
                    <p className="text-xs text-slate-500 mb-2">Sent <span className="font-mono">{lastSent.eventName}</span> to {lastSent.endpointNames.length} endpoint(s):</p>
                    <div className="space-y-1.5">
                      {appAttempts.filter(a => a.messageId === lastSent.messageId).sort((a, b) => a.createdAt - b.createdAt).map(a => (
                        <div key={a.id} className="flex items-center justify-between text-xs bg-slate-50 rounded-lg px-3 py-1.5">
                          <span className="text-slate-600">{a.endpointName} – attempt {a.attemptNo}</span>
                          <StatusBadge status={a.status} />
                        </div>
                      ))}
                    </div>
                    <button onClick={() => setActiveTab('history')} className={`${btnGhost} mt-2`}>View in history <ExternalLink size={12} /></button>
                  </div>
                )}
              </div>
            )}

            {/* HISTORY TAB */}
            {activeTab === 'history' && (
              <div className="bg-white border border-slate-200 rounded-xl">
                <div className="flex items-center justify-between px-4 py-3 border-b border-slate-100 flex-wrap gap-2">
                  <p className="text-sm font-medium text-slate-700">Delivery attempts</p>
                  <div className="flex items-center gap-2">
                    <select value={historyEndpointFilter} onChange={e => { setHistoryEndpointFilter(e.target.value); setHistoryPage(1); }} className="text-xs border border-slate-200 rounded-lg px-2 py-1.5">
                      <option value="all">All endpoints</option>
                      {appEndpoints.map(ep => <option key={ep.id} value={ep.id}>{ep.name}</option>)}
                    </select>
                    <select value={historyStatusFilter} onChange={e => { setHistoryStatusFilter(e.target.value); setHistoryPage(1); }} className="text-xs border border-slate-200 rounded-lg px-2 py-1.5">
                      <option value="all">All statuses</option>
                      <option value="IN_FLIGHT">In flight</option>
                      <option value="SUCCEEDED">Succeeded</option>
                      <option value="FAILED_RETRYING">Retrying</option>
                      <option value="DEAD">Dead</option>
                    </select>
                  </div>
                </div>
                {filteredAttempts.length === 0 ? (
                  <div className="p-8 text-center text-sm text-slate-400">No delivery attempts yet. Send a message from the Push message tab.</div>
                ) : (
                  <>
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="text-left text-xs text-slate-400 border-b border-slate-100">
                          <th className="px-4 py-2 font-medium">Time</th>
                          <th className="px-4 py-2 font-medium">Event</th>
                          <th className="px-4 py-2 font-medium">Endpoint</th>
                          <th className="px-4 py-2 font-medium">#</th>
                          <th className="px-4 py-2 font-medium">Status</th>
                          <th className="px-4 py-2 font-medium">Response</th>
                          <th className="px-4 py-2 font-medium">Latency</th>
                          <th className="px-4 py-2 font-medium"></th>
                        </tr>
                      </thead>
                      <tbody>
                        {pageAttempts.map(a => (
                          <tr key={a.id} className="border-b border-slate-50 last:border-0">
                            <td className="px-4 py-2.5 text-xs text-slate-400 whitespace-nowrap">{timeAgo(a.createdAt)}</td>
                            <td className="px-4 py-2.5 font-mono text-xs text-slate-700">{a.eventName}</td>
                            <td className="px-4 py-2.5 text-slate-600">{a.endpointName}</td>
                            <td className="px-4 py-2.5 text-slate-500">{a.attemptNo}</td>
                            <td className="px-4 py-2.5"><StatusBadge status={a.status} /></td>
                            <td className="px-4 py-2.5 text-slate-500">{a.responseCode ?? '–'}</td>
                            <td className="px-4 py-2.5 text-slate-500">{a.latencyMs ? `${a.latencyMs}ms` : '–'}</td>
                            <td className="px-4 py-2.5">
                              {a.status === 'DEAD' && (
                                <button onClick={() => handleReplay(a)} className={btnGhost}><RefreshCw size={13} />Retry</button>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    <div className="flex items-center justify-between px-4 py-3 border-t border-slate-100">
                      <span className="text-xs text-slate-400">Page {historyPage} of {totalPages} · {filteredAttempts.length} attempt(s)</span>
                      <div className="flex gap-1.5">
                        <button disabled={historyPage <= 1} onClick={() => setHistoryPage(p => p - 1)} className={`${btnGhost} disabled:opacity-30 disabled:cursor-not-allowed`}>Previous</button>
                        <button disabled={historyPage >= totalPages} onClick={() => setHistoryPage(p => p + 1)} className={`${btnGhost} disabled:opacity-30 disabled:cursor-not-allowed`}>Next</button>
                      </div>
                    </div>
                  </>
                )}
              </div>
            )}
          </>
        )}
      </div>

      {/* MODALS */}
      {showNewEnvModal && (
        <Modal title="New environment" onClose={() => setShowNewEnvModal(false)}>
          <Field label="Name">
            <input className={inputCls} placeholder="e.g. staging" value={newEnvName} onChange={e => setNewEnvName(e.target.value)} autoFocus />
          </Field>
          <button onClick={createEnvironment} className={`${btnPrimary} w-full justify-center`}>Create environment</button>
        </Modal>
      )}

      {showNewAppModal && (
        <Modal title="New app" onClose={() => setShowNewAppModal(false)}>
          <Field label="Name">
            <input className={inputCls} placeholder="e.g. Acme Billing" value={newAppName} onChange={e => setNewAppName(e.target.value)} autoFocus />
          </Field>
          <button onClick={createApp} className={`${btnPrimary} w-full justify-center`}>Create app</button>
        </Modal>
      )}

      {showNewEventModal && (
        <Modal title="New event type" onClose={() => { setShowNewEventModal(false); setEventError(''); }}>
          <Field label="Name">
            <input className={`${inputCls} font-mono`} placeholder="e.g. payment.completed" value={newEventName} onChange={e => setNewEventName(e.target.value)} autoFocus />
          </Field>
          {eventError && <p className="text-xs text-rose-600 mb-3 flex items-center gap-1"><AlertCircle size={13} />{eventError}</p>}
          <button onClick={createEvent} className={`${btnPrimary} w-full justify-center`}>Create event</button>
        </Modal>
      )}

      {showNewEndpointModal && (
        <Modal title="New endpoint" onClose={() => setShowNewEndpointModal(false)} wide>
          <Field label="Name">
            <input className={inputCls} placeholder="e.g. Production" value={newEpName} onChange={e => setNewEpName(e.target.value)} autoFocus />
          </Field>
          <Field label="URL">
            <input className={`${inputCls} font-mono`} placeholder="https://example.com/webhooks/relay" value={newEpUrl} onChange={e => setNewEpUrl(e.target.value)} />
          </Field>
          <Field label="Simulate failures before success (demo only)">
            <input type="number" min="0" max="6" className={inputCls} value={newEpFailCount} onChange={e => setNewEpFailCount(e.target.value)} />
          </Field>
          <button onClick={createEndpoint} className={`${btnPrimary} w-full justify-center`}>Create endpoint</button>
        </Modal>
      )}

      {revealSecret && (
        <Modal title="Signing secret" onClose={() => setRevealSecret(null)}>
          <p className="text-xs text-slate-500 mb-3">This is the only time you'll see the secret for <strong className="font-medium text-slate-700">{revealSecret.name}</strong>. Store it now.</p>
          <div className="flex items-center gap-2 bg-slate-50 border border-slate-200 rounded-lg px-3 py-2 mb-4">
            <code className="text-xs font-mono text-slate-700 flex-1 truncate">{revealSecret.secret}</code>
            <button onClick={() => navigator.clipboard?.writeText(revealSecret.secret)} className="text-slate-400 hover:text-slate-600"><Copy size={14} /></button>
          </div>
          <button onClick={() => setRevealSecret(null)} className={`${btnPrimary} w-full justify-center`}><Check size={15} />Done</button>
        </Modal>
      )}

      {subsPanelEndpoint && (
        <Modal title="Manage subscriptions" onClose={() => setSubsPanelEndpoint(null)}>
          <p className="text-xs text-slate-500 mb-3">Choose which event types this endpoint receives.</p>
          {appEvents.length === 0 ? (
            <p className="text-sm text-slate-400">No event types exist in this app yet.</p>
          ) : (
            <div className="space-y-1">
              {appEvents.map(ev => {
                const checked = subscriptions.some(s => s.endpointId === subsPanelEndpoint && s.eventId === ev.id);
                return (
                  <label key={ev.id} className="flex items-center gap-2.5 px-2.5 py-2 rounded-lg hover:bg-slate-50 cursor-pointer">
                    <input type="checkbox" checked={checked} onChange={() => toggleSubscription(subsPanelEndpoint, ev.id)} className="rounded border-slate-300 text-indigo-600 focus:ring-indigo-500/30" />
                    <span className="font-mono text-xs text-slate-700">{ev.name}</span>
                  </label>
                );
              })}
            </div>
          )}
          <button onClick={() => setSubsPanelEndpoint(null)} className={`${btnPrimary} w-full justify-center mt-4`}>Done</button>
        </Modal>
      )}
    </div>
  );
}
```

---

## 12. What to do first in the new session

Confirm you've read this handoff, then start TDD on `Event`: same rhythm as `App` — a Red test for `EventService.create` before `EventService` exists at all, working bottom-up (Service → Repository → Controller). Ask me for the entity's exact field list and any design decisions (e.g., should `Event` be deletable — still an open question per §7 above) before writing the first test.
