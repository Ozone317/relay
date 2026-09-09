# Relay — Frontend Integration Brief (v3, 2026-09-09)

**Audience:** a fresh Claude session that will build Relay's dashboard frontend **in a brand-new,
separate repository**, working largely unsupervised.

**Read this alongside three other documents**, each of which does a different job:

| Document | What it is | How much to trust it |
|---|---|---|
| `RELAY_HANDOFF.md` | Backend history, architecture, conventions | Accurate for backend internals. It is **not** an API contract. |
| `relay-mvp-prd (1).md` | The product requirements | Aspirational. Several parts are **not built** — see §9. |
| `relay-frontend-artifact.jsx` | A React mockup with local state, no network calls | Use for **layout, copy, and interaction shape only**. Its data model is invented — see §9. |
| **This document** | The API contract, verified against source | **This wins on every conflict.** Every fact here was read out of the code on 2026-09-05. |

When this document and any other disagree, **this document is right** and the other is stale. Where
that happens, it is called out explicitly rather than left for you to discover.

**Verification status.** Every endpoint, DTO and status code below was read out of the backend
source. The auth flow specifically — register, the CORS response headers, the exact `Set-Cookie`
attributes, CSRF-header enforcement, refresh, logout, post-logout refresh, duplicate-email 409 and
validation 400 — was additionally exercised with `curl` against a **live running backend** and the
responses are quoted verbatim. Trust the quoted responses over any prose that contradicts them.

⚠️ **Reading the source proves the contract, not that the endpoint runs.** This document's first
version described `GET /attempts` correctly and the endpoint was nonetheless completely broken —
it threw a PostgreSQL error on every call, which the backend then reported as a `401`. Fixed on
2026-09-06 (§5.8). The lesson for you: **if an endpoint behaves in a way this document cannot
explain, that is worth reporting, not working around silently.** One such report has already
turned out to be a genuine backend bug.

**v2 (2026-09-07): added §5.8.1, the replay endpoint (PRD FR-5.3).** Verified against the merged,
tested source (`POST .../attempts/{attemptId}/replay`, 345 backend tests passing) rather than against
a live curl session this time — if anything in §5.8.1 doesn't match what you observe, report it the
same way as any other discrepancy.

**v3 (2026-09-09): the flat attempts API is gone, replaced by a delivery-centric API — see §5.8.**
`GET /attempts`, `GET /attempts/{id}`, and `POST /attempts/{id}/replay` have been **deleted**, not
deprecated — there is no fallback and no transition window. A new `Delivery` resource sits between a
message-to-endpoint pairing and its attempts (§8's hierarchy is also updated). Verified directly
against the merged, tested source (`DeliveryController`, its DTOs, and its exception classes) on
2026-09-09, 344 backend tests passing. If you already built a History tab against the v2 contract,
it needs rework, not patching.

---

## 1. Your mission

Build the Relay dashboard as a **standalone frontend application in a new git repository**. The
backend is finished, running, and must not be modified. You own the frontend end to end: repo setup,
stack choice, implementation, tests, and verification against the real running backend.

**The backend repository is off limits.** If you believe you have found a backend bug, write it down
in your repo's `NOTES.md` and keep going with a documented workaround. Do not edit backend code.

**Definition of done:** a user can register, log in, create an environment → app → event → endpoint,
subscribe the endpoint to the event, send a test message, watch delivery attempts appear with real
statuses, filter and page through history, open an attempt to see its payload and response, and log
out — all against the real backend, with no mocked data anywhere in the shipped app.

---

## 2. Running the backend

The backend lives in a separate checkout. From that repository's root:

```bash
docker compose -f docker-compose.dev.yml up
```

This starts Postgres (host port **5433**), RabbitMQ (**5672**, management UI **15672**,
`relay`/`relay`), and the Spring Boot app on **http://localhost:8080** under the `docker` profile.

It requires a `.env` file in the backend repo root containing `JWT_SECRET=<any-long-random-string>`.
That file is gitignored and will not exist in a fresh clone — without it the app fails to start with
a `PlaceholderResolutionException`. If the backend owner has not given you one, create it.

**Health check:** `GET http://localhost:8080/actuator/health` → `{"status":"UP"}`. This route is
public; use it to confirm the backend is up before debugging anything else.

⚠️ **Do not use `curl -sf .../actuator/health` as a readiness gate.** If RabbitMQ is not running, the
app itself is fully up and serving HTTP, but health aggregates the broker and reports **`DOWN` with
status 503** — so `-f` treats it as failure and you will wait forever for a backend that is already
answering requests. Poll for any HTTP response (`curl -s -o /dev/null -w '%{http_code}'` returning
non-000), or just call `/api/v1/auth/register`. Verified the hard way on 2026-09-05.

Alternatively the backend runs on H2 in-memory with `./mvnw spring-boot:run` (no Docker, no
persistence across restarts, still port 8080). **Every endpoint in this document works in that
mode** — it is how the whole contract below was verified — but with no RabbitMQ nothing is actually
delivered, so attempts stay at `CREATED` and health reads `DOWN`. Use Docker Compose when you need
to watch deliveries progress.

---

## 3. The single most important constraint: your dev server must run on port 3000

CORS is configured with **one exact allowed origin**, read from `relay.auth.allowed-origin`, which
ships as:

```
relay.auth.allowed-origin=http://localhost:3000
```

It **cannot** be a wildcard, because the API sends credentials (`allowCredentials=true`), and the
CORS spec forbids `Access-Control-Allow-Origin: *` with credentials. So:

- Your dev server **must** serve on `http://localhost:3000`. Not 3001, not 5173, not 127.0.0.1:3000
  (that is a different origin string and will be rejected).
- Vite defaults to 5173 and Next.js defaults to 3000 — if you pick Vite, pin the port explicitly.
- If port 3000 is genuinely unavailable, the backend owner must change that property and restart.
  You cannot fix this from the frontend.

The rest of the CORS policy, verified in `SecurityConfig.corsConfigurationSource`:

| Setting | Value |
|---|---|
| Allowed origins | `http://localhost:3000` (exactly one, from config) |
| Allowed methods | `GET, POST, PUT, PATCH, DELETE, OPTIONS` |
| Allowed headers | `*` |
| Allow credentials | `true` |
| Applies to | `/**` |

---

## 4. The auth model — read this section twice

Relay uses a **short-lived access token held in memory** plus a **long-lived refresh token in an
httpOnly cookie**. This is not a single-JWT system; the PRD says it is, and the PRD is out of date.

### 4.1 The two credentials

| | Access token | Refresh token |
|---|---|---|
| Format | JWT (HMAC-SHA256) | Opaque random string (SHA-256 hashed server-side) |
| Lifetime | **15 minutes** (`expiresIn: 900`, in **seconds**) | **30-day sliding idle window** |
| Where it lives | **JavaScript memory only** | `relay_refresh` httpOnly cookie |
| How it is sent | `Authorization: Bearer <token>` | Automatically by the browser |
| Revocable | No — valid until it expires | Yes, instantly, server-side |

**Never put the access token in `localStorage` or `sessionStorage`.** Hold it in a module variable
or a React context/store. It is deliberately short-lived precisely so that losing it on a page
refresh is cheap — you re-obtain one by calling `/auth/refresh`, which the cookie authenticates.

**Revocation is bounded, not instant, and this is deliberate.** Logging out revokes the refresh
token immediately so no new access token can be minted, but an already-issued access token stays
valid for up to its remaining 15 minutes, because protected routes perform no database read. Do not
design UI that promises instant global sign-out.

### 4.2 The refresh cookie, exactly

Set by the backend as (`RefreshCookieFactory`):

```
Set-Cookie: relay_refresh=<opaque>; Path=/api/v1/auth; Max-Age=2592000;
            HttpOnly; Secure; SameSite=Strict
```

Four consequences you must design around:

1. **`HttpOnly`** — your JavaScript cannot read it. Do not try. You never need its value; the
   browser attaches it for you.
2. **`Path=/api/v1/auth`** — the browser sends it **only** to `/api/v1/auth/*`. It is deliberately
   absent from every business endpoint. Those use the Bearer token instead.
3. **`SameSite=Strict`** — this still works from `localhost:3000` to `localhost:8080`, because
   SameSite compares registrable domains and **ignores the port**. Both are `localhost`, so they are
   the same *site* even though they are different *origins*. ⚠️ This stops being true the moment the
   frontend and backend are deployed on different domains — flag it rather than silently shipping a
   login that works locally and fails in production.
4. **`Secure`** (`relay.auth.cookie-secure=true`) — normally means HTTPS only, but browsers treat
   `http://localhost` as a trustworthy origin, so it works in local dev. If you ever observe the
   cookie not being stored, verify this first; the backend owner can set
   `relay.auth.cookie-secure=false` for local work.

### 4.3 `credentials: 'include'` is mandatory

`fetch` does **not** send or store cross-origin cookies unless you ask. Every call to
`/api/v1/auth/*` — register, login, refresh, logout — must set `credentials: 'include'`, or the
cookie is silently never stored and never sent, and login will appear to work while refresh
mysteriously 401s. Setting it globally on your API client is simplest and harmless.

### 4.4 The CSRF header

`/auth/refresh` and `/auth/logout` are authenticated by an **ambient** cookie — the browser attaches
it whether or not your app initiated the request. That is exactly the CSRF condition, so those two
routes additionally require a custom header:

```
X-Relay-Auth: 1
```

The value is never inspected; only presence matters. Missing it returns **403** with
`{"status":403,"message":"Missing X-Relay-Auth header"}` before any controller runs.

**Exactly two routes require it: `/api/v1/auth/refresh` and `/api/v1/auth/logout`.** Not register,
not login, not logout-all, not any business endpoint. Sending it everywhere is harmless, and is the
simpler thing to do.

### 4.5 The flows, precisely

**Register / Login** — `POST /auth/register` or `/auth/login` with `credentials:'include'`. You get
`{accessToken, expiresIn}` in the body and the cookie via `Set-Cookie`. Store the access token in
memory; note the expiry as `Date.now() + expiresIn*1000`.

**Authenticated call** — `Authorization: Bearer <accessToken>`.

**Refresh** — `POST /auth/refresh` with `credentials:'include'` and `X-Relay-Auth: 1`, **no body, no
bearer token**. Returns a fresh `{accessToken, expiresIn}` and re-sets the cookie with a slid
expiry. There is **no token rotation** — the refresh token value stays the same.

**When to refresh:** on app boot (to recover a session after a page reload), proactively shortly
before `expiresIn` elapses, and reactively on any 401 from a business endpoint — retrying the
original request once after a successful refresh. Make concurrent 401s share a single in-flight
refresh promise rather than firing N refreshes.

**If refresh returns 401**, the session is genuinely over: clear in-memory state and route to login.

**Logout** — `POST /auth/logout` with `credentials:'include'` and `X-Relay-Auth: 1`. Returns 204 and
clears the cookie. Discard the in-memory access token yourself. Note it returns **204 even when no
cookie was sent** — logout is deliberately idempotent and never reports whether a session existed.

**Logout everywhere** — `POST /auth/logout-all`, authenticated by **Bearer token**, not the cookie,
and it does **not** need the CSRF header. ⚠️ Because it needs a live access token, it fails with
**401** if the token has expired — so refresh first, then call it, or handle the 401 by refreshing
and retrying once. It also clears the refresh cookie, so treat it as a full sign-out locally too.

---

## 5. Complete API reference

Base URL `http://localhost:8080`. All bodies are JSON. All IDs are UUID strings. All timestamps are
ISO-8601 UTC strings (`"2026-09-05T10:15:30.123456Z"`).

**Every endpoint in §5.2 onward requires `Authorization: Bearer <accessToken>`.** Only the five auth
routes and `/actuator/health` are public.

### 5.1 Auth — `/api/v1/auth`

| Method | Path | Auth | CSRF hdr | Body | Success |
|---|---|---|---|---|---|
| POST | `/register` | none | no | `{email, password}` | **201** `{accessToken, expiresIn}` + cookie |
| POST | `/login` | none | no | `{email, password}` | **200** `{accessToken, expiresIn}` + cookie |
| POST | `/refresh` | cookie | **yes** | none | **200** `{accessToken, expiresIn}` + cookie |
| POST | `/logout` | cookie | **yes** | none | **204** + cleared cookie |
| POST | `/logout-all` | **Bearer** | no | none | **204** + cleared cookie |

`email` must be a valid email; `password` must be **at least 8 characters** on register (not
enforced on login). `expiresIn` is **seconds** (900).

Failures: `409` email already taken (register), `401` bad credentials (login), `401`
`"Invalid refresh token"` (refresh — see §6.2), `403` missing CSRF header.

⚠️ The 409 body has a **doubled message**, a pre-existing backend cosmetic bug:
`"User already exists with email: User with email probe@example.com already exists."` The exception
constructor prepends a prefix to a caller-supplied string that is already a full sentence. Do not
render it raw — show your own copy ("That email is already registered") and key off the **status
code**, not the message text.

### 5.2 Environments — `/api/v1/environments`

| Method | Path | Body | Success |
|---|---|---|---|
| POST | `/` | `{name, description?}` | **201** `EnvironmentResponseDto` |
| GET | `/` | — | **200** `EnvironmentResponseDto[]` |
| GET | `/{id}` | — | **200** `EnvironmentResponseDto` |
| PATCH | `/{id}` | `{description}` | **200** `EnvironmentResponseDto` |
| DELETE | `/{id}` | — | **204** |

`EnvironmentResponseDto` = `{id, name, description, updatedAt}`.

⚠️ Update is **PATCH**, and it can change **only `description`**. There is no way to rename an
environment. Do not build a rename control.

`name` must be non-blank; `description` max 500 chars.

### 5.3 Apps — `/api/v1/environments/{environmentId}/apps`

| Method | Path | Body | Success |
|---|---|---|---|
| POST | `/` | `{name}` | **201** `AppResponseDto` |
| GET | `/` | — | **200** `AppResponseDto[]` |
| GET | `/{appId}` | — | **200** `AppResponseDto` |
| DELETE | `/{appId}` | — | **204** |

`AppResponseDto` = `{id, name, environmentId, createdAt}`. There is **no** app update endpoint.

### 5.4 Events — `/api/v1/environments/{environmentId}/apps/{appId}/events`

| Method | Path | Body | Success |
|---|---|---|---|
| POST | `/` | `{name}` | **201** `EventResponseDto` |
| GET | `/` | — | **200** `EventResponseDto[]` |

`EventResponseDto` = `{id, name, appId, createdAt, subscriberCount}`.

⚠️ There is **no** get-by-id, update, or delete for events. Events are create-and-list only. Note
that `subscriberCount` on the **create** response is always `0`; re-fetch the list to get real
counts.

Event names are unique per app — a duplicate returns **409**.

### 5.5 Endpoints — `/api/v1/environments/{environmentId}/apps/{appId}/endpoints`

| Method | Path | Body | Success |
|---|---|---|---|
| POST | `/` | `{name, url}` | **201** `EndpointCreatedDto` |
| GET | `/` | — | **200** `EndpointResponseDto[]` |
| GET | `/{endpointId}` | — | **200** `EndpointResponseDto` |
| PATCH | `/{endpointId}` | `{name?, url?, active?}` | **200** `EndpointResponseDto` |
| DELETE | `/{endpointId}` | — | **204** |

- `EndpointCreatedDto` = `{id, name, url, active, appId, **signingSecret**, createdAt, updatedAt}`
- `EndpointResponseDto` = `{id, name, url, active, appId, createdAt, updatedAt}` — **no secret**

⚠️ **The signing secret is returned exactly once, on creation, and can never be retrieved again.**
You must show it immediately in a "copy this now, you will not see it again" modal. The artifact
already models this correctly. There is no regenerate endpoint.

`url` must be a valid `http://` or `https://` URL. PATCH requires **at least one** of the three
fields to be non-null, otherwise **400**. Duplicate endpoint name in an app → **409**.

### 5.6 Subscriptions — `.../apps/{appId}/endpoints/{endpointId}/subscriptions`

| Method | Path | Success |
|---|---|---|
| PUT | `/{eventId}` | **201** `SubscriptionResponseDto` |
| GET | `/` | **200** `SubscriptionResponseDto[]` |
| DELETE | `/{eventId}` | **204** |

`SubscriptionResponseDto` = `{id, appId, eventId, eventName, endpointId, createdAt}`.

⚠️ Subscribe is **PUT** with the event id in the **path** and **no request body**, and it returns
**201**, not 200. This is the only `PUT` in the whole API.

### 5.7 Messages — `.../apps/{appId}/messages`

| Method | Path | Body | Success |
|---|---|---|---|
| POST | `/` | `{eventId, body}` | **201** `MessageResponseDto` |

`body` is arbitrary JSON (an object, not a string). `MessageResponseDto` =
`{id, appId, eventId, eventName, body, createdAt}`.

Posting a message fans out to every **active** endpoint subscribed to that event and enqueues
delivery. If there are none, it returns **422** `NoActiveSubscribersException` — surface this as a
helpful message, it is the most common user error in this flow.

⚠️ There is **no** `GET /messages`. A message's payload is only ever readable embedded in a
delivery's detail response (§5.8), not an attempt's — see the DTO field lists below.

⚠️ There is **no idempotency key, and there is never going to be one.** The PRD specifies one
(FR-2.3) and the artifact has an input for it; both are stale. Dropped by explicit product decision
on 2026-09-07. **Do not build the field at all** — not even disabled with a "coming soon" note.
See §9.

### 5.8 Deliveries (delivery history) — `.../apps/{appId}/deliveries`

⚠️ **`GET /attempts`, `GET /attempts/{id}`, and `POST /attempts/{id}/replay` no longer exist.** This
is a clean breaking replacement, not an addition — the entire flat attempts API described in v2 of
this document was deleted before this branch merged. If your History tab was built against `/attempts`,
rework it against the routes below rather than expecting both to work.

A **Delivery** is the new addressable resource: one message-to-endpoint pairing, encompassing every
attempt (original + retries + manual replays) made to deliver it. It sits between `Message`/`Endpoint`
and `Attempt` in the object hierarchy (§8). The delivery list is now your primary history view; the
attempt list is a drill-down **within** a delivery, not a flat table across the whole app.

| Method | Path | Success |
|---|---|---|
| GET | `/` | **200** `Page<DeliverySummaryDto>` |
| GET | `/{deliveryId}` | **200** `DeliveryDetailDto` |
| GET | `/{deliveryId}/attempts` | **200** `Page<DeliveryAttemptSummaryDto>` |
| GET | `/{deliveryId}/attempts/{attemptId}` | **200** `AttemptDetailDto` |
| POST | `/{deliveryId}/replay` | **201** `DeliverySummaryDto` |

Query parameters on the top-level list (`GET /`), all optional:

| Param | Type | Notes |
|---|---|---|
| `endpointId` | UUID | |
| `status` | `AttemptStatus` | exact enum name, see §7 — filters on the delivery's **current** (latest-attempt) status |
| `createdFrom` | ISO-8601 instant | inclusive, e.g. `2026-09-05T00:00:00Z` |
| `createdTo` | ISO-8601 instant | inclusive |
| `page` | int | 0-based, default `0` |
| `size` | int | default `20` |
| `sort` | string | e.g. `createdAt,desc` |

`GET /{deliveryId}/attempts` takes only standard pagination params (`page`, `size`, `sort`) — no
filters, since you're already scoped to one delivery.

⚠️ **There is no default sort on either list endpoint.** Neither the top-level delivery query nor
the nested attempts query declares a `@PageableDefault` or an `ORDER BY`, so results come back in
whatever order the database returns them. **Always pass `sort=createdAt,desc` explicitly** on the
delivery list, and something like `sort=attemptNo,asc` on the nested attempt list, or your tables
will appear to shuffle between pages.

Date filters on the delivery list apply to the **delivery's own** `createdAt` (when the underlying
message/endpoint pairing was first created), not to individual attempt timestamps.

**`DeliverySummaryDto`** (list rows, and the replay response body) = `{id, eventName, endpointId,
endpointName, status, attemptCount, latestAttemptNo, responseCode, latencyMs, createdAt,
lastAttemptAt}`. `status` reflects the **latest** attempt's status — this is what "the delivery's
status" means throughout this document. `responseCode`/`latencyMs` are the latest attempt's, for the
same reason.

**`DeliveryDetailDto`** (single-delivery view) = all of `DeliverySummaryDto` minus nothing, plus
`{messageId, payload}` — `payload` is the parent message's JSON body. **There is no embedded attempt
array here.** If you want the attempt history, call `GET /{deliveryId}/attempts` separately — this
was a deliberate split so the (potentially large) detail payload isn't duplicated across every
delivery-detail fetch.

**`DeliveryAttemptSummaryDto`** (rows in the nested attempts list) = `{id, attemptNo, status,
responseCode, latencyMs, createdAt}` — deliberately lightweight, no `responseBody`/`lastError`/
`payload`. For the full detail of one specific attempt, call
`GET /{deliveryId}/attempts/{attemptId}`.

**`AttemptDetailDto`** (single-attempt detail) = `{id, deliveryId, attemptNo, status, responseCode,
responseBody, lastError, latencyMs, nextRetryAt, createdAt, updatedAt}`. Note it carries `deliveryId`,
not a message/endpoint pair — to show the parent message's payload alongside an attempt, fetch it
separately via the delivery detail endpoint. `responseCode`/`responseBody` are null when there was no
HTTP response at all (timeout, DNS failure, connection refused); `lastError` carries the reason in
that case. They are mutually exclusive — render whichever is present.

**Response shape** for both list endpoints is the standard Spring `Page` envelope:

```json
{
  "content": [ /* DeliverySummaryDto[] or DeliveryAttemptSummaryDto[] */ ],
  "totalElements": 42,
  "totalPages": 3,
  "number": 0,
  "size": 20,
  "first": true,
  "last": false,
  "numberOfElements": 20,
  "empty": false
}
```

Treat `content`, `totalElements`, `totalPages`, `number`, and `size` as the stable fields.

### 5.8.1 Replay — `POST .../apps/{appId}/deliveries/{deliveryId}/replay`

This is what the artifact's Retry button should actually call. No request body.

**Success:** `201` with the delivery's **updated** `DeliverySummaryDto`, reflecting the newly created
attempt (`attemptCount` incremented, `latestAttemptNo` bumped, `status` back to `CREATED`/`IN_FLIGHT`,
`responseCode`/`latencyMs` reset to whatever the new attempt has so far — typically null immediately
after creation). The **new attempt's id is not in this response body.** If you need it — e.g. to open
its detail view — call `GET /{deliveryId}/attempts` again and take the row with the highest
`attemptNo`, then `GET /{deliveryId}/attempts/{newAttemptId}` for the full detail (there's nothing
interesting to show yet: `responseCode`/`responseBody`/`lastError` are all still null at creation
time).

**Failure modes, all `ApiError` shape (§6.1), now keyed by `deliveryId` rather than `attemptId`:**

| Status | Condition | Suggested UI handling |
|---|---|---|
| 404 | delivery not found, or not owned by you | Same as any other 404 — shouldn't happen from a real history row; if it does, refresh the list |
| 409 | the delivery's current (latest-attempt) status is not `DEAD` | **Hide or disable the Retry button for any status other than `DEAD`.** Don't rely on the 409 to gate this client-side. |
| 409 | the delivery's endpoint has since been deactivated | Show something like "Can't replay: this endpoint is no longer active" |
| 409 | another replay/attempt is already in flight for this exact message+endpoint pairing | Rare (only hit by a double-click, two tabs racing, or a retry that hadn't finished settling) — treat as "already retrying, try again in a moment" and don't auto-retry the request yourself |

All three 409s carry distinct `message` text (not collapsed into one generic string like the
refresh-token 401 is) — safe to surface `message` directly in a toast if you want, though writing your
own copy per status is still the more polished option.

**A replay is genuinely repeatable.** If the replay itself also fails, the delivery lands back on
`DEAD` (never left in a pending/retrying state), and you can call replay again on the **same**
`deliveryId`. There's no cap on how many times a delivery can be manually replayed — each replay is
independent and just keeps incrementing `attemptNo`.

**What NOT to build:** there is no bulk-replay endpoint, and no way to replay anything other than a
single `DEAD` delivery by id. If you want a "retry all dead deliveries for this endpoint" convenience
feature, that's a client-side loop over individual replay calls, not a backend capability — and
nobody has asked for it, so don't build it speculatively.

---

## 6. Error contract

### 6.1 The standard error body

Every error raised inside a controller comes back as `ApiError`:

```json
{
  "status": 404,
  "message": "Endpoint not found with id: 0f9c...",
  "timestamp": "2026-09-05T10:15:30.123456Z",
  "fieldErrors": null
}
```

Validation failures (**400**) populate `fieldErrors` as a field-name → message map and set `message`
to `"Validation failed"`. Render these against the matching form inputs.

| Status | When |
|---|---|
| 400 | Bean-validation failure — see `fieldErrors` |
| 401 | Bad login credentials; invalid/expired/missing refresh token |
| 403 | Missing `X-Relay-Auth` header on `/refresh` or `/logout` |
| 404 | Environment / app / event / endpoint / delivery / attempt not found, **or not owned by you** |
| 409 | Email already registered; duplicate event name; duplicate endpoint name |
| 422 | `POST /messages` with no active subscribed endpoints |
| 500 | A genuine server-side fault. `{"status":500,"message":"Internal server error"}` |

⚠️ **A `500` used to be impossible to see.** Until 2026-09-06 any unhandled backend exception was
returned as `401 "Authentication required"` — the JWT filter does not run on Spring's error
dispatch, so the error response itself failed authentication. Treat a `401` as "re-authenticate"
and a `500` as "backend fault, report it"; before the fix those two were indistinguishable, which
is how a broken SQL statement passed for an auth bug.

**Ownership failures are 404, not 403.** Requesting another user's resource is indistinguishable
from it not existing — deliberate. Do not write UI that tries to tell them apart.

### 6.2 Two responses that are *not* `ApiError`

These are written by filters, before the exception handler exists, so they have only two fields:

- **Missing or invalid Bearer token** — `401 {"status":401,"message":"Authentication required"}`
- **Missing CSRF header** — `403 {"status":403,"message":"Missing X-Relay-Auth header"}`

Your error parser must tolerate the absence of `timestamp` and `fieldErrors`.

### 6.3 `/refresh` deliberately tells you nothing

Every refresh failure — unknown token, revoked token, expired token, no cookie at all — returns the
identical `401 "Invalid refresh token"`. This is intentional (it would otherwise leak whether a
session had been logged out). Do not attempt to branch on the reason; there is exactly one
user-facing outcome: the session is over, go to login.

---

## 7. Enums and constants

```
AttemptStatus: CREATED | IN_FLIGHT | SCHEDULED | SUCCEEDED | FAILED_RETRYING | DEAD
```

⚠️ **Six values. The artifact's `StatusBadge` handles only four** — it is missing `CREATED` and
`SCHEDULED`, and silently falls back to rendering anything unknown as "In flight", which would
misreport a queued retry as an active one. Handle all six.

Meanings, since they are not self-evident:

| Status | Means |
|---|---|
| `CREATED` | Row written, queued, not yet picked up by a worker |
| `IN_FLIGHT` | A worker has claimed it and the HTTP call is in progress |
| `SCHEDULED` | Failed, and the retry is **parked in a backoff queue** waiting for its delay |
| `SUCCEEDED` | Delivered (terminal) |
| `FAILED_RETRYING` | This attempt failed; a successor attempt row exists |
| `DEAD` | All 6 attempts exhausted (terminal) — this is the dead-letter state |

Retry schedule: attempt 1 immediate, then 30s, 2m, 10m, 1h, 6h. **Max 6 attempts.** So a fully
failing delivery reaches `DEAD` after roughly 7.5 hours. Design the history view for slow-moving
data; polling every few seconds is plenty, and there is no websocket or SSE endpoint.

Outbound webhook headers Relay sends to customer endpoints (useful for docs/tooltips, not for your
own requests): `relay-id`, `relay-timestamp`, `relay-signature` — no `x-` prefix.

---

## 8. Object hierarchy

```
User
 └── Environment        (free-form name, e.g. "Sandbox")
       └── App
             ├── Event      (a type name, e.g. "payment.completed")
             ├── Endpoint   (a real URL + signing secret)
             │     └── Subscription  (endpoint ·—· event, via PUT)
             ├── Message    (one publish of an event + payload)
             └── Delivery   (one message → endpoint pairing; new since the PRD, see §9)
                   └── Attempt   (one HTTP try toward delivering it — original, retry, or replay)
```

**`Delivery` is new since v2 of this document (added 2026-09-09).** A delivery is created once per
message/endpoint pairing at fan-out time; every subsequent attempt toward it — the original try, its
automatic retries, and any manual replays — is a child row under that same delivery. §5.8 is the
routes for it.

Almost every URL is nested `/environments/{environmentId}/apps/{appId}/...`, so your app needs a
persistent notion of "current environment" and "current app" — the artifact's two header dropdowns
model this correctly and you should keep that shape. Persisting the selected IDs in `localStorage`
is fine; they are not secrets.

---

## 9. Where the PRD and the artifact lie to you

Every item here was verified absent from the backend source on 2026-09-05, **except Replay, added
2026-09-07 — see the note below the table.** For everything else: **do not build UI that calls
these — the endpoints do not exist.**

| Thing | PRD/artifact says | Reality |
|---|---|---|
| **Idempotency key** | PRD FR-2.3; artifact has an input for it | **Cut from the product, not merely unbuilt.** `MessageCreateDto` is `{eventId, body}` only and the `messages` table has no such column — and as of 2026-09-07 that is a settled decision, not a backlog item. **Remove the input from the Push message form entirely.** Do not ship it disabled, and do not report it as a missing feature. |
| **Dead-letter view** | PRD FR-5.4, a dedicated screen | No separate route. It is the normal deliveries list filtered `status=DEAD`. |
| **Message list** | Implied by the PRD | No `GET /messages`. Payloads are visible only via delivery detail (§5.8), not attempt detail. |
| **`Delivery` as an object** | PRD §4.1's hierarchy diagram shows `Message`/`Endpoint` → `Attempt` directly, with nothing in between | Not a PRD/reality gap in the usual sense — `Delivery` didn't exist when the PRD was written. It's a real, addressable resource now (added 2026-09-09), sitting between `Message`/`Endpoint` and `Attempt`. See §8 and §5.8. |
| **Auth model** | PRD §7/§13.2: "24-hour single JWT, no refresh token" | **Reversed.** 15-minute access token + refresh cookie. §4 of this document is correct. |
| **Rename environment** | Implied by generic CRUD | PATCH accepts `description` only. |
| **Edit/delete events** | Implied by generic CRUD | Create and list only. |
| **Edit apps** | Implied by generic CRUD | Create, read, delete only. |
| **Rate limiting** | PRD FR-6.1 | Not built. No 429s to handle. |
| **Simulated failures** | Artifact's "Simulate failures before success" field | Pure mockup. Point endpoints at a real URL — `webhook.site`, or a local server you control — to see failures. |
| **Artifact's data layer** | `useState` + `setTimeout` fake deliveries | Entirely fictional. Delete all of it. Keep the JSX, the Tailwind classes, and the copy. |

✅ **Replay / retry (PRD FR-5.3) is no longer in this table — it's real now.** As of 2026-09-09,
`POST .../deliveries/{deliveryId}/replay` exists — see §5.8.1 for the full contract (this moved off
`attemptId` as of v3 of this document; if you built against the old `.../attempts/{attemptId}/replay`
route, it's gone). **If you already built the artifact's Retry button as a no-op or hid it, wire it up
now.** Two behavior changes versus what the artifact currently does: the button must be **DEAD-only**
(hidden or disabled for every other delivery status — the artifact's mockup doesn't gate this), and a
successful replay creates a **new** attempt under the same delivery — the response is the delivery's
updated summary, not the new attempt itself (see §5.8.1 for how to fetch the new attempt's id if you
need it).

---

## 10. Suggested approach

**Stack.** The PRD says React + Next.js; the artifact is React + Tailwind + `lucide-react` icons.
Next.js (App Router) defaults to port 3000, which the CORS constraint in §3 makes convenient. Plain
Vite + React is equally fine if you pin the port. Either way this is a **client-side authenticated
dashboard** — the access token lives in browser memory, so do not attempt server-side rendering of
authenticated data. If you use Next.js, mark the authenticated tree `"use client"`.

**Build order** — each step is independently verifiable against the running backend:

1. **Repo + API client first.** Set up the project, then a single fetch wrapper that owns the base
   URL, `credentials:'include'`, the Bearer header, the `X-Relay-Auth` header, error parsing (§6,
   including the two non-`ApiError` shapes), and the refresh-on-401 retry with a shared in-flight
   promise. Get this right before any UI — every later step depends on it.
2. **Auth screens.** Register, login, logout, and session recovery on reload via `/auth/refresh`.
   Verify by reloading mid-session and staying logged in.
3. **Environment + app selectors.** The header dropdowns and "current selection" state.
4. **Events and endpoints tabs**, including the show-secret-once modal.
5. **Subscriptions** (the `PUT`-based checkbox panel).
6. **Push message tab.** Handle the 422 no-subscribers case explicitly.
7. **History tab.** Now a delivery list (§5.8): pagination, filters, `sort=createdAt,desc`, a
   delivery detail view, and a drill-down to that delivery's attempts (`sort=attemptNo,asc`), all six
   statuses.
8. **Replay.** Wire the Retry button (§5.8.1) to `POST .../deliveries/{deliveryId}/replay`, gated to
   `DEAD` deliveries only. If you built step 7 against the v2 flat-attempts contract, this is likely
   the one place you need to revisit existing code rather than just add new code — the routes, DTOs,
   and the id the replay call keys off of have all changed.

**Testing.** Unit-test the API client's auth logic — especially that a 401 triggers exactly one
refresh and one retry, and that concurrent 401s do not stampede. Consider Playwright for the
register → send → observe-attempt loop against a real backend.

---

## 11. Verify before you claim done

Do not report success without having actually run these against a live backend:

1. `curl http://localhost:8080/actuator/health` returns `UP`.
2. Register a new user in the browser; confirm in devtools that a `relay_refresh` cookie was
   **stored** (Application → Cookies) and that the response carried `accessToken`.
3. Hard-reload the page. You should stay logged in — proving `/auth/refresh` works, including the
   `X-Relay-Auth` header and `credentials:'include'`.
4. Temporarily drop the `X-Relay-Auth` header from `/refresh` and confirm you get **403**. Restore
   it. This proves you are actually sending it rather than being saved by something else.
5. Full loop: environment → app → event → endpoint (capture the secret) → subscribe → send a
   message → a delivery appears in the list. Point the endpoint at a `webhook.site` URL and confirm
   the request really arrives with `relay-id`, `relay-timestamp`, `relay-signature` headers.
6. Point an endpoint at a URL that returns 500 and confirm the delivery's status moves
   `IN_FLIGHT → FAILED_RETRYING`, and that a successor attempt appears under it (`attemptCount`
   incremented) as `SCHEDULED`. This is the single best proof your status rendering is right.
7. Log out; confirm a subsequent `/auth/refresh` returns 401 and you land on the login screen.
8. Find (or create) a `DEAD` delivery, click Replay, and confirm a **new** attempt appears under it
   (fetch `GET /{deliveryId}/attempts` again — the highest `attemptNo` row is the new one, with a
   different `id` than any prior attempt). Then try Replay on a non-`DEAD` delivery (e.g. a
   `SUCCEEDED` one) via a raw request if the button is hidden for it in the UI, and confirm you get a
   `409`, not a silent success.

If any step fails, write down what you observed rather than assuming the backend is wrong — it is
covered by 344 passing tests, so the frontend is the more likely suspect.
