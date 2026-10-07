# P07 Idempotent Message Admission Investigation Report

**Date:** 2026-10-06
**Revision investigated:** `761b539` (`main`, merge of P06)
**Design result:** READY TO IMPLEMENT (owner-approved)
**Implementation status (2026-10-07):** Implementation is present; full-suite verification and coordinated deployment remain outstanding

## Executive result

Current Relay message ingestion is atomic but not idempotent. Each successful HTTP submission creates a new Message UUID, a new Delivery for every active subscription, and Attempt #1 for every Delivery. PostgreSQL commits that graph together, but nothing relates two client retries.

Disposable PostgreSQL 16 probes reproduced durable duplication for sequential retry, commit-with-response-loss retry, and concurrent identical submissions. The selected repair is an insert-first PostgreSQL row uniquely keyed by User + App + client key. It joins the existing Message/fan-out transaction and points to a preallocated Message ID through a deferred foreign key. Concurrent losers wait for the winning transaction and either replay its committed result or acquire after rollback. No durable progress state or Redis authority is required.

The existing endpoint keeps `Idempotency-Key` optional. A supplied valid key receives the strong guarantee independently of plan/tier. P07 defines no expiry or cleanup; arbitrary expiration would introduce an unsupported duplicate window.

## Current acceptance path

```text
HTTP POST /api/v1/environments/{environmentId}/apps/{appId}/messages
→ SecurityConfig requires authentication
→ JwtAuthenticationFilter validates Bearer JWT
→ AuthenticatedUser supplies durable user UUID
→ Spring validates MessageCreateDto
→ MessageController.create
→ MessageService.create opens outer transaction
→ App ownership lookup
→ Event ownership lookup
→ active subscription resolution
→ reject 422 if none
→ insert Message
→ AttemptService joins transaction
→ insert one Delivery + Attempt #1 per subscription
→ commit
→ map MessageResponseDto
→ HTTP 201
```

Durable effects are only Message, Delivery, and Attempt rows. Ready publication happens later through the P06-owned dispatcher. Attempt allocation metrics are deliberately recorded before commit and are not durable cardinality.

No ingestion header, idempotency record, fingerprint, uniqueness constraint, or request-result replay exists on current `main`.

## Reproduced failure evidence

The external probe used V1–V14 migrations, real PostgreSQL, two active subscriptions, separate connections, and client barriers. It did not use arbitrary sleeps.

| Failure mode | Evidence |
|---|---|
| Sequential identical retry | Two distinct Message IDs; final counts `Messages=2, Deliveries=4, Attempts=4` |
| Response-loss equivalent | First transaction committed and its result was discarded; retry produced another ID; final counts `2/4/4` |
| Concurrent identical submissions | Both backends were open after authorization/subscriber reads, then released together; both committed; final counts `2/4/4` |

This result is permitted by all current constraints because Delivery uniqueness is `(message_id, endpoint_id)` and each retry receives a fresh Message ID.

The selected primitive was also probed directly:

| Winner outcome | Loser behavior |
|---|---|
| Winner open | Loser blocked on PostgreSQL `transactionid` lock during `INSERT ... ON CONFLICT DO NOTHING` |
| Winner commits | Loser inserted zero rows and read the winner's committed result |
| Winner rolls back | Loser inserted the now-free identity and became owner |

All temporary probes and the disposable container were removed.

## Selected contract

### Header

- Optional on the existing API.
- Missing means every request is a new submission; no payload deduplication.
- Present means 1–255 US-ASCII bytes matching RFC 9110 `token` grammar.
- Exact validation expression: ``^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,255}$``.
- Case-sensitive and opaque; no trimming or normalization.
- Empty, malformed, overlength, non-ASCII, or repeated/comma-combined values return `400` before writes.

### Scope

```text
(user_id, app_id, operation=message.create, idempotency_key)
```

- `user_id` is the durable `users.id`, not email, JWT text, or credential ID. It is authoritative today because User is the persisted root reached by every Environment and App ownership lookup, and current-main has no separate durable ownership entity.
- Production ownership is `users.id -> environments.user_id -> apps.environment_id`. The JWT filter constructs `AuthenticatedUser` from the durable user UUID; the controller passes that UUID to `MessageService`; and the existing App and Event queries verify the complete ownership chain before idempotency acquisition. The scope must never trust a client-supplied `user_id`.
- JWTs, sessions, future API keys, and other credential types are authentication mechanisms, not durable ownership identities. While User remains the ownership boundary, every credential must resolve to the same user ID, so rotation or alternate credentials cannot bypass the identity.
- If ownership later moves to Account, Organization, Workspace, or another durable entity, that project must explicitly map/backfill Apps and their existing idempotency rows to the same new owner during a coordinated cutover. It must not reinterpret a credential ID as ownership or leave an idempotency row attached to an obsolete User while its App moves. P07 does not design that model and needs no speculative schema change today.
- App is explicit. In the current domain model its Environment ownership link has no reassignment path, so Environment is transitively fixed for P07; any future ownership migration must move dependent idempotency authority coherently.
- The dedicated table is the `message.create` operation namespace.
- Event is fingerprint content, not scope.
- The same key in another App is independent.

### Fingerprint

Version 1 is:

```text
(eventId UUID, body as PostgreSQL jsonb)
```

The authoritative Message already stores both values. Conflict comparison uses Event UUID equality and PostgreSQL `jsonb` equality, so whitespace/object ordering do not matter, while array order and JSON types do. Subscriber state, credentials, and future quota state are excluded.

### Response and errors

- Same fingerprint: return the original Message as `201 Created` with the same domain fields and no new fan-out.
- Different fingerprint: `409 Conflict`, message `Idempotency-Key is already associated with a different message request`.
- Response is reconstructed from the Message; P07 does not store full HTTP bytes or promise JSON member-order/header replay.
- No replay-indicator response header is added.

### Rejections

Authentication, validation, App/Event ownership, no-subscriber, future quota, and unexpected fan-out failures do not establish committed idempotency state. A no-subscriber owner provisionally inserts the identity, but the existing `422` rolls back the entire transaction. A later retry reevaluates current state.

A retry of an already committed identity returns the original result before subscriber or future quota evaluation.

## Selected PostgreSQL primitive

V15 adds `message_idempotency` with:

- primary key `(user_id, app_id, idempotency_key)`;
- `fingerprint_version=1` check;
- unique `message_id`;
- database acceptance timestamp;
- deferred, initially deferred FK to `messages(id)`;
- key octet-length check and `C` collation.

The winning flow preallocates a Message UUID and inserts the complete identity first using:

```sql
INSERT ...
ON CONFLICT (user_id, app_id, idempotency_key) DO NOTHING
RETURNING message_id, accepted_at
```

The same transaction then resolves subscribers, passes the future admission point, and inserts Message/Deliveries/Attempts. The deferred FK is checked at commit, so no committed authority can lack its Message.

The unique insert is the ownership/contention point. Transaction commit is the externally visible acceptance linearization point.

`READ COMMITTED` is a protocol requirement: after a losing insert waits and returns no row, its next statement needs a fresh snapshot to observe the winner's commit. The plan now requires `SHOW transaction_isolation` from the transaction-bound connection inside the actual `MessageService.create` → repository acquisition path for both winner and loser. The same tests associate those observations with backend PIDs and prove the winner-commit and winner-rollback paths through observed PostgreSQL blocking. Annotation or configuration inspection alone is not evidence.

### Durable authority consistency

V15's foreign keys prove independently that `user_id`, `app_id`, and deferred `message_id` exist. Existing foreign keys also define the current owner of an App transitively and the App of a Message. They do **not** compare those derived values with the values stored in `message_idempotency`:

```text
i.user_id = environments.user_id reached from apps.id = i.app_id
i.app_id  = messages.app_id reached from messages.id = i.message_id
```

The P07 service protocol enforces both equalities by authorizing App/Event first, passing only the authenticated `userId` and authorized App to acquisition, constructing Message from that App, and replaying through the fully scoped Message query. Adding database-only equality would require redundant composite uniqueness/ownership columns or a cross-table trigger, so V15 is unchanged.

Tests and rollout diagnostics must join authority → Message → App → Environment and report zero mismatches. A narrow test will insert cross-wired rows with valid foreign-key targets, without disabling constraints, to document the database boundary. It uses the existing independent Message→App and Message→Event foreign keys to let authorization/fingerprint comparison succeed while the authority App and Message App differ; fully scoped replay must then fail loudly instead of creating or repairing a result.

## Why alternatives were rejected

| Candidate | Rejection |
|---|---|
| Uniqueness directly on Message | Forces Message upsert/constraint recovery before ownership is known; JPA conflict handling poisons the transaction and couples retention/protocol to Message persistence |
| `SELECT FOR UPDATE` only | Cannot lock an absent key; still needs insert uniqueness |
| User/App sentinel lock | Serializes unrelated keys and duplicates future quota locking |
| Advisory lock | Adds hash/collision and lock-order protocol while still needing a durable unique result row |
| Separately committed `IN_PROGRESS` | Splits the atomic boundary and needs stale-owner recovery |
| Redis `SETNX` | Cannot join Message/fan-out/quota commit and reopens identity after eviction/restore |

## Concurrency and crash behavior

| Case | Durable result and retry behavior |
|---|---|
| Before key acquisition | No state; retry acquires |
| After acquisition, before Message | Rollback removes provisional row; retry acquires |
| Mid-fan-out | Identity, Message, partial fan-out all roll back |
| All writes, before commit | All roll back |
| Commit, process dies before response | One full graph remains; retry returns original |
| Duplicate while open | Waits on unique transaction; commit replays, rollback allows acquisition |
| Duplicate after commit | Returns original with no writes |
| Conflicting payload while open | Waits; winner commit gives `409`, rollback allows new evaluation |
| No subscribers | `422`; no durable identity |
| Cleanup race | None: P07 adds no cleanup |
| Retry after expiry | No expiry exists; original remains authoritative |

No durable `IN_PROGRESS` status is needed because an uncommitted row plus PostgreSQL lock ownership already represents execution in progress and disappears on crash.

The winner-commit regression proves the loser is blocked, reports `read committed` from its actual transaction, returns no ownership row after release, and observes the winner in its next statement. The winner-rollback regression proves the same blocking/isolation preconditions, then shows the loser acquires and leaves no orphan authority.

## Retention decision

P07 records do not expire and are not cleaned up. The key is not reusable.

This defers the customer-visible idempotency horizon to the broader retention project. That future work must coordinate Message/payload retention, compact fingerprint preservation if payloads are redacted, active Delivery lifetime, PostgreSQL-time cutoffs, retry/delete races, bounded batches, and P06 scheduler ownership.

Deleting a committed authority is the moment duplication becomes legal. It must never happen as an undocumented storage cleanup.

## Existing-invariant audit

- **P04:** No claim/completion/recovery path changes. Initial Attempts remain `CREATED`, generation 0, unclaimed.
- **P05:** Initial fan-out still creates Delivery + Attempt #1. Retry/replay Endpoint-before-Delivery locks and Delivery-scoped sequencing are untouched.
- **P00:** New PostgreSQL tests use shared Testcontainers and deterministic cleanup. Keyed fixture cleanup order is Attempts → Deliveries → idempotency → Messages.
- **P06:** No scheduler, recurring callback, executor, or cleanup task is introduced.

## Future quota/accounting extension

Only a new accepted operation reaches the future hook:

```text
idempotency owner (or keyless new request)
→ resolve nonempty fan-out
→ future hard quota + accepted-message usage admission
→ Message + Deliveries + Attempts
→ commit
```

A committed idempotent replay returns before this hook. The future hook must use mandatory propagation in the same transaction and may uniquely key its usage fact by Message/result identity. P07 already supplies User, App/Environment, Message ID, and PostgreSQL acceptance timestamp. It does not prematurely add policy revisions, period tables, quota counters, or billing fields.

## Migration and rollout

V15 is additive and empty; historical traffic cannot be assigned client keys and is not backfilled. Old binaries continue writing Messages without idempotency rows.

Schema-first is structurally safe, but an uncoordinated mixed old/new fleet cannot advertise the guarantee: an old binary may ignore the header and duplicate the operation. Required release order:

1. apply V15;
2. deploy P07 binaries without external contract exposure;
3. drain old binaries from ingestion traffic, or route keyed requests only to new binaries;
4. run keyed sequential/concurrent smoke tests and cardinality audit;
5. advertise the header.

After clients can rely on the feature, application rollback to a key-ignorant binary is unsafe. Leave V15 in place and roll forward. Dropping the table discards correctness history.

The rollout audit additionally checks that every authority's `user_id` equals the User reached through its App and every authority's `app_id` equals its Message's `app_id`. This is required because V15 deliberately uses individual foreign keys rather than claiming composite cross-table enforcement.

## Deterministic regression matrix

The implementation plan requires PostgreSQL-backed coverage for:

- first acceptance;
- keyless duplicate semantics;
- sequential same-key replay;
- concurrent same-key replay with observed transaction-ID blocking;
- `SHOW transaction_isolation = read committed` inside the actual winner and loser service/acquisition transactions;
- same key/different Event or body;
- owner rollback then waiter takeover;
- commit-with-response-loss retry;
- no-subscriber rollback and later success;
- same key across Apps;
- different credentials resolving to one User;
- exact `jsonb` fingerprint semantics;
- Message/Delivery/Attempt cardinality for N subscribers;
- no expiry;
- V15 deferred-FK/unique/check behavior;
- committed User/App/Message coherence through the full ownership join;
- valid-FK cross-wired corruption detection and fail-loud replay behavior;
- P00/P04/P05/P06 regression selections.

Latches/barriers create ordering. Database activity/lock views prove waiting. Timeouts only fail stuck tests; no sleep creates a correctness interleaving.

## Unresolved decisions

No owner decision blocks P07 implementation.

Explicitly deferred decisions are:

- making the header mandatory in a future versioned contract;
- finite retention/expiry and customer reuse guarantees;
- future multi-user/Account/Organization/Workspace ownership beyond today's durable User identity, including the required coordinated migration of Apps and existing idempotency authorities;
- actual quota/accounting policy and schema;
- idempotency for manual replay or other operations;
- a response replay-indicator header.

These do not require redesign of the P07 authority or a speculative P07 schema column. The future ownership project must honor the documented migration obligation rather than silently changing the meaning of `user_id`.

## Deliverables

- Design: `docs/superpowers/specs/2026-10-06-p07-idempotent-message-admission-design.md`
- Plan: `docs/superpowers/plans/2026-10-06-p07-idempotent-message-admission.md`
- This report: `docs/reviews/2026-10-06-p07-idempotent-message-admission-investigation-report.md`

No production code, test code, migration, build configuration, or runtime configuration was modified by the investigation/design task.

## P07 implementation verification and rollout gate (2026-10-07; Task 8 review correction)

**Implementation code revision:** `c2f436d1db76b36eebc9b68c6d350f802d85fdd6`.

### Verification commands and outcomes

Formatting:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw spotless:check
```

Result: failed repository-wide. Spotless reported 416 Java files, 201 needing formatting and 215 already clean. It named `src/test/java/com/example/relay/attempt/mapper/AttemptMapperTest.java` and `src/test/java/com/example/relay/attempt/infrastructure/AttemptExecutionRepositoryPostgresTest.java`, then summarized “Violations also present in 199 other files.” The default output does not enumerate those remaining files. The first documented P07-scoped selector was incorrect and selected zero files, so it was not evidence of a pass. The review correction generated an exact selector from all 21 Java files changed since base `761b539d66b14fb09ceff150fc4ccb60e855170b`, including `GlobalExceptionHandler.java`:

```bash
p07_files_regex=$(git diff --name-only 761b539d -- '*.java' | sed 's/[.]/[.]/g' | sed 's|^|.*/|' | paste -sd '|' -)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw spotless:apply "-DspotlessFiles=$p07_files_regex"
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw spotless:check "-DspotlessFiles=$p07_files_regex"
```

The selector matched 21 of 21 Java files changed since the base. `spotless:apply` reported 21 selected, 12 changed to clean and 9 cache-skipped. The same-scope `spotless:check` reported 21 clean, 0 needing changes, and 21 cache-skipped (`BUILD SUCCESS`). The post-format P07 regression selection passed 46 tests with zero failures/errors/skips. The 12 formatter edits are mechanical only.

Full test suite:

```bash
env JWT_SECRET=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
  RELAY_EMAIL_SENDER_EMAIL=test@example.com RELAY_EMAIL_SENDER_NAME=Relay BREVO_API_KEY=test-key \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw test
```

The command was started at 16:50 IST and interrupted at 16:58 IST with exit 130 after more than two minutes without progress. It hung in the existing non-P07 test `BrevoEmailSenderTest.send_throwsEmailSendException_on429RateLimited`. The test enqueues one HTTP 429 response at `BrevoEmailSenderTest.java:120`; Apache HttpClient retries after the 429, and the main test thread blocks in `DefaultBHttpClientConnection.receiveResponseHeader` → `BrevoEmailSender.send(BrevoEmailSender.java:48)` → `BrevoEmailSenderTest.java:124`, waiting for another MockWebServer response. No Surefire report was produced for that class. The completed reports at interruption covered 169 test classes and 877 tests, all with 0 failures, 0 errors, and 0 skipped. This is not a full-suite pass. Earlier baseline verification had already observed the same pre-existing email-phase hang.

Focused regression results from completed tasks:

| Selection | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| Full P07 selection | 42 | 0 | 0 | 0 |
| P04/P05 regression selection | 49 | 0 | 0 | 0 |
| P00/P06 regression selection | 18 | 0 | 0 | 0 |

The precise command lines and selected class lists are in `task-6-report.md` and `task-7-report.md` in the SDD evidence directory. Task 6's service test exercised the real `MessageService.create → MessageIdempotencyRepository.tryAcquire` transaction and queried `SHOW transaction_isolation` through its transaction-bound JDBC connection. Both transactions observed `read committed`; owner PID 109 and contender PID 110 were distinct. PostgreSQL reported `wait_event_type=Lock`, `wait_event=transactionid`, `pg_blocking_pids(110)={109}`. This observation was made for winner commit/replay, fingerprint conflict, and winner rollback/takeover. The winner-commit loser's next statement in the same transaction read and reused the committed authority. The rollback waiter acquired the identity and committed one coherent graph.

### PostgreSQL catalog and coherence audit

Against the migrated PostgreSQL 16 database, the actual catalog output was:

```text
conname|pg_get_constraintdef|condeferrable|condeferred
ck_message_idempotency_fingerprint_version|CHECK ((fingerprint_version = 1))|f|f
ck_message_idempotency_key_length|CHECK (((octet_length((idempotency_key)::text) >= 1) AND (octet_length((idempotency_key)::text) <= 255)))|f|f
fk_message_idempotency_message|FOREIGN KEY (message_id) REFERENCES messages(id) DEFERRABLE INITIALLY DEFERRED|t|t
message_idempotency_app_id_fkey|FOREIGN KEY (app_id) REFERENCES apps(id)|f|f
message_idempotency_user_id_fkey|FOREIGN KEY (user_id) REFERENCES users(id)|f|f
pk_message_idempotency|PRIMARY KEY (user_id, app_id, idempotency_key)|f|f
uk_message_idempotency_message|UNIQUE (message_id)|f|f
(7 rows)

indexname|indexdef
pk_message_idempotency|CREATE UNIQUE INDEX pk_message_idempotency ON public.message_idempotency USING btree (user_id, app_id, idempotency_key)
uk_message_idempotency_message|CREATE UNIQUE INDEX uk_message_idempotency_message ON public.message_idempotency USING btree (message_id)
(2 rows)
```

The required mismatch query joined `message_idempotency → apps → environments` and `message_idempotency → messages` and returned `(0 rows)`. The smoke authority row returned `user_matches_app_owner=t` and `app_matches_message=t`. This demonstrates again that the database enforces each individual FK but the two cross-row equality checks belong to the authorized service/transaction protocol.

The source audits were run:

```bash
rg -n 'message.*idempot|Idempotency-Key' src/main/java src/main/resources
rg -n 'Redis|StringRedisTemplate|@Scheduled|TaskScheduler|IN_PROGRESS|expires_at' \
  src/main/java/com/example/relay/message src/main/resources/db/migration/V15__add_message_idempotency.sql
```

Inspection found the PostgreSQL repository/controller/header path and expected API exception names. The P07 message package and V15 had no Redis authority, process scheduler, durable progress state, or expiry field. Broader matches belong to existing email-provider idempotency, password-reset/verification Redis and cleanup, and P06 delivery scheduling; P07 added none of these.

### Local two-instance HTTP smoke

A local disposable PostgreSQL 16 database and RabbitMQ container were started. The same packaged P07 artifact, built with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -DskipTests package`, ran as two independent app processes on ports 18081 and 18082 against the shared database. The two processes migrated/validated schema V15.

- Instance 1 keyed request: `201`, Message ID `b9c73b78-24e3-44eb-b5fc-9310c36b7a9b`.
- Instance 2 replay with the same key/fingerprint: `201`, same Message ID and acceptance timestamp.
- Instance 2 same key with a changed body: `409`, stable `ApiError` message.
- Two keyless identical submissions across the two instances: both `201`, distinct Message IDs `a5b12020-8283-4a3a-8bcb-a0a229ae11fb` and `ec4c7d39-c74e-4733-ac70-dddce01fe9ba`.
- Stable post-smoke SQL cardinality: `1 authority / 3 Messages / 3 Deliveries / 3 Attempts`. Each Message had one Delivery and one Attempt with `attempt_no=1`.

The first local run left RabbitMQ enabled long enough for P06's dispatcher/worker to retry an intentionally unreachable webhook, so Attempts advanced independently of admission. The smoke fixture was reset in the disposable database, RabbitMQ was stopped, and the HTTP scenarios were rerun; the final cardinality above is the admission result with asynchronous webhook execution isolated. The test-only token/user and all fixture rows lived only in the disposable database. Both application processes and both named containers were stopped and removed.

This local smoke proves cross-process behavior on the P07 artifact. It is not a coordinated non-production rollout against the deployment fleet. Production rollout remains gated on applying V15, ensuring no key-ignorant binary can receive keyed ingestion, running the coordinated keyed replay/conflict/keyless/cardinality smoke, and confirming the catalog/coherence query returns zero mismatches.

### Review and remaining notes

Task 6 adversarial review approved the service/concurrency regression diff with no findings. Task 7 adversarial review approved the invariant inventory and P00/P04/P05/P06 gates with no findings. One parked Task 5 Minor remains: `MessageServiceTest`'s keyed-owner mock returns the proposed UUID instead of a distinct acquired-ID sentinel. Production code correctly uses the acquired result; this is a test-strength improvement. It is not a Task 8 contract deviation.

No production code, test code, migration, build file, or runtime configuration changed during Task 8. The build produced only ignored `target/` artifacts; temporary infrastructure was removed. The Task 8 documentation commit is limited to the spec and this investigation report.
