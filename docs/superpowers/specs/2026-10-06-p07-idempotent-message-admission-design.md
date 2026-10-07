# P07 Idempotent Message Admission Design

**Date:** 2026-10-06
**Status:** Implementation is present; full-suite verification and coordinated deployment remain outstanding
**Scope:** PostgreSQL-authoritative idempotency for `POST /api/v1/environments/{environmentId}/apps/{appId}/messages`
**Out of scope:** tiers, entitlements, quotas, billing, API-key issuance, generic deduplication, replay-operation idempotency, payload limits, retention cleanup, and production implementation in this design task

## 1. Decision summary

Relay will accept an optional `Idempotency-Key` header on the existing message-ingestion endpoint.

- If the header is absent, every valid request remains a new submission. Relay does not hash the payload for deduplication and does not synthesize a key.
- If the header is present and valid, the durable identity is the authenticated User ID plus target App plus the message-create operation plus the case-sensitive key.
- The current `users.id` UUID is the durable user identity. Authentication adapters must resolve every credential, including future API keys, to this same durable user UUID. Email, JWT text, and credential IDs are not scope dimensions.
- In the current domain model, a target App is created under one Environment and exposes no ownership reassignment path. Environment is therefore part of the scope transitively through `app_id`, not a redundant uniqueness column; a future ownership migration must update dependent idempotency authority coherently.
- The operation is `message.create`; the dedicated `message_idempotency` table supplies its namespace, so future operations use separate authorities rather than sharing keys accidentally.
- Fingerprint version 1 is the tuple `(eventId UUID, body JSON value)`. Event identity is not part of the key scope. Body comparison uses PostgreSQL `jsonb` equality.
- Same scope/key and the same fingerprint returns the originally accepted Message with `201 Created` and creates no new Message, Delivery, Attempt, or future usage admission.
- Same scope/key and a different fingerprint returns `409 Conflict`.
- A unique-row insert is the contention/ownership point. The transaction commit is the externally visible acceptance linearization point.
- No durable `IN_PROGRESS` state is used. PostgreSQL transaction visibility and unique-index waiting represent in-progress ownership.
- Failed or rolled-back acceptance leaves no durable idempotency row and does not consume the key.
- P07 does not expire or clean up idempotency records. A later retention project must define a customer-visible reuse horizon before removing them.
- Idempotency is an API correctness primitive available independently of plan or tier.

## 2. Intent and success criteria

The purpose of P07 is to make an ambiguous message-ingestion result safely retryable. A client that loses the HTTP response after Relay commits must be able to repeat the same authorized operation and recover the original accepted Message rather than create another fan-out.

P07 succeeds when the following statement is true for a valid supplied key:

```text
message idempotency authority
+ Message
+ one Delivery per active subscription
+ Attempt #1 for each new Delivery
+ future accepted-message usage admission
------------------------------------------------
one PostgreSQL transaction and one committed decision
```

The mechanism must work across application instances and credentials, must preserve the P04/P05 execution and allocation invariants, and must not make Redis or an application-process mutex a correctness authority.

## 3. Current-main behavior

### 3.1 HTTP, authentication, and authorization path

1. `SecurityConfig` requires authentication for the message endpoint.
2. `JwtAuthenticationFilter` validates the Bearer token and constructs `AuthenticatedUser` from the token's user UUID and email.
3. Spring MVC validates `MessageCreateDto`: `eventId` and `body` must bind non-null.
4. `MessageController.create` receives `environmentId`, `appId`, the authenticated principal, and the DTO.
5. `MessageService.create` opens the outer `@Transactional` boundary.
6. `AppRepository.findByIdAndEnvironmentIdAndEnvironmentUserId` proves the App belongs to the requested Environment and authenticated user UUID.
7. `EventService.getById` proves the Event belongs to the same App/Environment/user chain.
8. `SubscriptionRepository.findAllByEventIdAndEndpointActiveTrue` resolves active subscribers.
9. No subscribers produces `422 Unprocessable Entity` before Message creation.
10. `MessageMapper` constructs a new Message with a random UUID and application-clock timestamp; `MessageRepository.save` schedules its insert.
11. `AttemptService.createFromSubscriptionList` joins the existing transaction. For every active subscription it inserts one new Delivery and one Attempt with `attempt_no=1`, `status=CREATED`, `execution_generation=0`, and no execution claim.
12. The outer transaction commits Message, Deliveries, and Attempts together.
13. Only after the service returns does the controller map the Message to `MessageResponseDto` and return `201 Created`.

The production ownership chain is `users.id → environments.user_id → apps.environment_id`. `JwtAuthenticationFilter` extracts the user UUID, stores it in `AuthenticatedUser`, and `MessageController` passes `AuthenticatedUser.getId()` to `MessageService` as `userId`. `MessageService` does not trust that value as sufficient by itself: `AppRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(appId, environmentId, userId)` must resolve the target App, and `EventService.getById` repeats the App/Environment/User ownership chain for the requested Event. P07 must preserve these checks before idempotency acquisition. A future credential adapter must supply the same authenticated durable user UUID rather than making credential identity part of admission scope.

### 3.2 Durable effects and non-durable observations

On successful acceptance, the only durable effects are PostgreSQL rows:

- one `messages` row;
- one `deliveries` row per active subscription;
- one initial `attempts` row per Delivery.

Ready-work publication occurs later through P06-owned scheduling/dispatch. The initial Attempt allocation metric is incremented before commit and explicitly is not an exact durable count; P07 does not treat that metric as authority.

The existing transaction integration test proves that a failure during Attempt persistence rolls back the Message and Deliveries. No current header handling, request fingerprint, idempotency table, or relevant uniqueness constraint exists.

## 4. Reproduction evidence

A disposable PostgreSQL 16 database was migrated with V1 through V14 and seeded with one User, one Environment, one App, one Event, and two active subscriptions. An external probe mirrored the exact ownership reads and Message/Delivery/Attempt writes. It used separate connections and client-side barriers; it did not use timing sleeps.

| Scenario | Durable result |
|---|---|
| Sequential identical submissions | Two distinct Message IDs; 2 Messages, 4 Deliveries, 4 Attempts |
| Commit followed by simulated response loss, then retry | The unobserved committed Message and retry Message were distinct; 2 Messages, 4 Deliveries, 4 Attempts |
| Concurrent identical submissions | Both backends were observed `idle in transaction` after authorization/subscriber reads and before a simultaneous barrier release; both committed without failure; 2 Messages, 4 Deliveries, 4 Attempts |

The absence of a shared uniqueness identity means the current Delivery uniqueness constraint is irrelevant: it is scoped by the newly generated Message ID, so each duplicate Message receives an independent valid fan-out.

A second disposable-table probe tested the selected PostgreSQL primitive:

- a losing `INSERT ... ON CONFLICT DO NOTHING` blocked on the winner's uncommitted unique row with PostgreSQL reporting a `transactionid` lock wait;
- after winner commit, the loser inserted zero rows and read the winner's result;
- after winner rollback, the loser inserted the identity and became the owner.

The probes and database container were removed after evidence collection.

## 5. Public API contract

### 5.1 Optional header

`Idempotency-Key` is optional on the existing endpoint.

- Header absent: current non-idempotent behavior is preserved.
- Header present: it must be valid and Relay must provide the strong P07 guarantee.
- Header empty, malformed, too long, or repeated: `400 Bad Request`; Relay performs no acceptance write.
- Idempotency behavior is not conditional on plan, tier, or entitlement.

A future versioned API may make the same header mandatory by changing request validation only. The database authority and service protocol do not change.

### 5.2 Header syntax

The value is a case-sensitive RFC 9110 `token` containing 1 through 255 US-ASCII bytes. The accepted characters are:

```text
A-Z a-z 0-9 ! # $ % & ' * + - . ^ _ ` | ~
```

The validation expression is:

```regex
^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,255}$
```

Because every accepted character is one byte in UTF-8, the character and octet limits are identical. Leading/trailing whitespace is not trimmed. Values are opaque: Relay does not lowercase, parse, normalize, or assign meaning to them. Commas are invalid, which also causes a servlet-combined repeated header to fail rather than silently choose one value.

The database repeats the length guard with `octet_length(idempotency_key) BETWEEN 1 AND 255`. API validation owns the full token grammar.

### 5.3 Durable scope

The durable identity is:

```text
(user_id, app_id, operation=message.create, idempotency_key)
```

Dimension semantics:

| Dimension | Rule |
|---|---|
| User | Current durable value is `users.id`. It is Relay's persisted ownership root today, and every Environment reaches exactly one User through `environments.user_id`. Future JWT/API-key/session adapters must resolve to this same user UUID while User remains the ownership boundary. Email, token text, and credential ID are excluded. |
| Environment | An App's current domain-immutable `environment_id` determines it. It is transitively part of scope and authorization, but is not redundantly stored in the unique key. The database foreign key preserves existence, while the Java model exposes no ownership reassignment path. |
| App | Explicit scope dimension. The same key may be used independently in two Apps, including Apps in one Environment. |
| Operation | The dedicated `message_idempotency` table is the `message.create` namespace. A future replay or other operation uses a separate table/namespace. |
| Event | Not a scope dimension. It is part of the fingerprint, so changing Event with the same scoped key conflicts. |

Authorization is evaluated before idempotency lookup. An unauthorized credential cannot probe whether a key exists. The `user_id` used for the idempotency scope comes from the authenticated principal, and the existing App/Environment/User lookup must succeed before acquisition. Two different authorized credentials resolving to the same user UUID and App share the same identity.

Authentication credentials and durable ownership are different concepts. JWTs, sessions, and future API keys prove that a caller may act as a User; they do not become idempotency scope. Credential issuance, rotation, or replacement must continue resolving to `users.id`, so changing credentials cannot create a second identity for the same User/App/key.

If Relay later introduces Account, Organization, Workspace, or another durable ownership root, that project must migrate idempotency authority as ownership data rather than merely changing authentication. Existing `message_idempotency` rows must remain attached to the same durable owner that governs their App. The migration must provide an explicit mapping/backfill and a coordinated cutover for Apps and idempotency rows; it must not reinterpret a credential ID as owner identity or leave existing rows scoped to an obsolete User while the App moves to a new owner. The future model and migration mechanics are intentionally outside P07. Current-main provides no distinct ownership entity, so P07 needs no speculative owner column or schema change.

### 5.4 Fingerprint version 1

The exact fingerprint is the semantic tuple:

```text
fingerprint_version = 1
event_id             = validated MessageCreateDto.eventId
body                 = validated MessageCreateDto.body as PostgreSQL jsonb
```

Comparison is performed by PostgreSQL against the authoritative Message referenced by the idempotency row:

```sql
stored_message.event_id = requested_event_id
AND stored_message.body = CAST(requested_body AS jsonb)
```

Consequences of `jsonb` equality are intentional:

- insignificant JSON whitespace and object-member order do not create a conflict;
- arrays remain order-sensitive;
- JSON type differences remain significant;
- PostgreSQL numeric equality governs equivalent numeric spellings;
- the validated/parsed JSON value, not raw HTTP bytes or serialization formatting, is the contract.

The App is already in scope and is not repeated in the fingerprint. Subscriber membership, Endpoint state, credential identity, headers, and future quota state are not request content and are excluded. A retry returns the original result even if subscribers or quota have changed since acceptance.

The idempotency row stores `fingerprint_version`; the Message row already durably stores `event_id` and `body`, avoiding a second payload copy. While an idempotency row exists, retention work must not delete or redact those Message fields unless it first replaces them with an equivalently comparable durable fingerprint under an explicit migration.

### 5.5 Same key, different fingerprint

If the scoped key is committed with a different fingerprint, Relay returns:

```text
HTTP 409 Conflict
message: "Idempotency-Key is already associated with a different message request"
```

The response uses the existing `ApiError` shape. P07 does not broaden that shared shape with an error-code field. The error discloses neither the original Event nor payload. No new rows or usage are created.

If the conflicting request races an uncommitted owner, it first waits for that transaction. A winner commit produces `409`; a winner rollback removes the provisional identity, allowing the waiting request to become the owner and be evaluated normally.

### 5.6 Response reconstruction

The idempotency row persists the accepted Message ID and acceptance timestamp. A retry loads that Message through the same User/Environment/App ownership scope and uses the existing mapper.

- Original and replay status: `201 Created`.
- Original and replay domain fields: same Message ID, App ID, Event ID/name, body value, and `createdAt`.
- Relay promises semantic response reconstruction, not byte-for-byte HTTP replay. JSON object-member order and incidental response headers are not persisted.
- The endpoint currently has no `Location` header; P07 does not invent one.
- P07 does not add a replay-indicator response header. Such a header can be added later without changing correctness.

Persisting a complete HTTP response would duplicate payloads and couple storage to presentation formatting without improving the current contract.

### 5.7 Pre-acceptance failures

| Failure | Durable idempotency state |
|---|---|
| Authentication failure | None; security rejects before controller/service admission |
| DTO or key validation failure | None |
| App ownership/not-found failure | None |
| Event ownership/not-found failure | None |
| No active subscribers | The transaction rolls back the provisionally inserted identity; no record remains; existing `422` is preserved |
| Future hard quota rejection for a new identity | Whole transaction rolls back; no idempotency or usage row remains |
| Unexpected Message/Delivery/Attempt failure | Whole transaction rolls back; no identity remains |
| Retry of a committed identity when subscribers are now absent | Return original result without re-evaluating subscribers |
| Retry of a committed identity when future quota is exhausted | Return original result without quota consumption or rejection |

Rejected requests are not cached. Retrying after the rejection reevaluates current authorization, subscribers, and future admission policy.

## 6. Authoritative data model

Add migration V15 with one operation-specific table:

```sql
CREATE TABLE message_idempotency (
    user_id             UUID         NOT NULL REFERENCES users(id),
    app_id              UUID         NOT NULL REFERENCES apps(id),
    idempotency_key     VARCHAR(255) COLLATE "C" NOT NULL,
    fingerprint_version SMALLINT     NOT NULL,
    message_id          UUID         NOT NULL,
    accepted_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_message_idempotency
        PRIMARY KEY (user_id, app_id, idempotency_key),
    CONSTRAINT uk_message_idempotency_message
        UNIQUE (message_id),
    CONSTRAINT ck_message_idempotency_key_length
        CHECK (octet_length(idempotency_key) BETWEEN 1 AND 255),
    CONSTRAINT ck_message_idempotency_fingerprint_version
        CHECK (fingerprint_version = 1),
    CONSTRAINT fk_message_idempotency_message
        FOREIGN KEY (message_id) REFERENCES messages(id)
        DEFERRABLE INITIALLY DEFERRED
);
```

The primary key is the ordinary relational concurrency authority. `COLLATE "C"` makes key equality byte-stable and case-sensitive. `message_id` uniqueness prevents two identities from naming one Message accidentally.

The deferred Message foreign key permits insert-first ownership while keeping a database-enforced complete committed result:

1. Relay preallocates the Message UUID.
2. Relay inserts the complete idempotency row before the Message exists.
3. Relay inserts the Message and fan-out later in the same transaction.
4. PostgreSQL checks the Message foreign key at commit.

No committed row can have a null/missing result, and rollback removes the entire provisional graph.

### 6.1 Ownership and result coherence

The intended committed invariant is:

```text
message_idempotency.user_id = owner of message_idempotency.app_id
message_idempotency.app_id  = app of message_idempotency.message_id
```

Current PostgreSQL constraints enforce only part of this invariant:

| Relationship | PostgreSQL guarantee | P07 protocol guarantee |
|---|---|---|
| Referenced rows exist | Separate foreign keys require `user_id`, `app_id`, and deferred `message_id` to name existing rows. Default `NO ACTION` behavior also prevents deleting a referenced row while the authority remains. | Not needed for existence. |
| App has an owner | `apps.environment_id → environments.id → environments.user_id → users.id` determines the App's current owner transitively. | Authorization resolves the App through `(appId, environmentId, authenticated userId)` before acquisition. |
| Authority User equals App owner | No cross-table equality constraint compares `message_idempotency.user_id` with the User reached from its App. | The repository receives only the authenticated `userId` and already-authorized `appId` from `MessageService`. |
| Message belongs to authority App | `messages.app_id` and `message_idempotency.app_id` each reference an App, but no constraint requires them to be equal. | The owner branch constructs the Message from the already-authorized App and the preallocated `message_id`; replay loads it through `findByIdAndAppIdAndEnvironmentIdAndUserId`. |

Therefore P07 does **not** claim that PostgreSQL alone makes every cross-wired authority row impossible. A composite FK for `(message_id, app_id)` would require an otherwise redundant unique constraint on `messages(id, app_id)`. Enforcing User/App equality would require redundant ownership columns, additional composite uniqueness through App/Environment, or a cross-table trigger. Those options add write/index cost or awkward cross-domain coupling while the only supported writer is already inside one checked transaction. V15 retains the approved schema and makes the service/transaction protocol authoritative for cross-row coherence.

Every successful PostgreSQL-backed acceptance test must join `message_idempotency` to `messages`, `apps`, and `environments` and assert both equalities. A narrow corruption test may insert deliberately cross-wired rows using only otherwise-valid foreign-key targets, without disabling constraints, to prove the schema boundary. For an end-to-end replay assertion, the fixture uses the existing independent Message→App and Message→Event foreign keys so App/Event authorization and fingerprint comparison can succeed while the Message's App still mismatches the authority App. Fully scoped Message loading must then fail loudly. It must never treat corruption as a cache miss, create a replacement Message, or repair data automatically.

`accepted_at` is returned by the acquisition insert using PostgreSQL `transaction_timestamp()` and is reused as the Message's `createdAt`. It is a database-authoritative acceptance timestamp available to future accounting. T1–T3 still own period-boundary and policy-revision semantics; P07 does not decide them. Reusing one timestamp avoids a split clock decision between the idempotency result and accepted Message.

## 7. Transaction protocol

`MessageService.create` remains the only outer acceptance transaction and explicitly uses PostgreSQL `READ_COMMITTED` isolation.

This is a protocol invariant, not merely an annotation choice. Under PostgreSQL READ COMMITTED, each command receives a new statement snapshot. After a losing `INSERT ... ON CONFLICT DO NOTHING` waits for the winner and returns no row, the loser's subsequent committed-result `SELECT` can observe the winner's newly committed authority. A transaction using one fixed snapshot could invalidate that loser path.

The implementation must positively observe `SHOW transaction_isolation` as `read committed` from the transaction-bound PostgreSQL connection inside the actual `MessageService.create` → `MessageIdempotencyRepository.tryAcquire` path. The deterministic winner-commit and winner-rollback tests also record that connection's backend PID, prove blocking through `pg_blocking_pids`/`pg_stat_activity`, and then exercise the real acquisition and result-read statements. Inspecting annotations or configuration is not accepted as evidence.

### 7.1 Keyless path

1. Authenticate and validate HTTP input.
2. Resolve/authorize App and Event.
3. Resolve active subscribers; reject `422` if none.
4. Allocate a fresh Message UUID and application-clock timestamp using current behavior.
5. Enter the future new-admission hook.
6. Persist the Message, Deliveries, and Attempt #1 rows.
7. Commit and return `201`.

No idempotency-table read or write occurs.

### 7.2 Keyed path

1. Authenticate and validate the DTO and key.
2. Resolve/authorize App and Event.
3. Generate a proposed Message UUID.
4. Execute the acquisition insert:

   ```sql
   INSERT INTO message_idempotency (
       user_id, app_id, idempotency_key, fingerprint_version, message_id, accepted_at
   ) VALUES (
       :userId, :appId, :key, 1, :messageId, transaction_timestamp()
   )
   ON CONFLICT (user_id, app_id, idempotency_key) DO NOTHING
   RETURNING message_id, accepted_at;
   ```

5. If one row returns, this transaction is the provisional owner:
   - resolve active subscribers;
   - reject and roll back if none;
   - enter the future new-admission/quota hook;
   - create the Message using returned `message_id`/`accepted_at`;
   - create one Delivery and Attempt #1 per resolved subscriber;
   - commit and return the Message.
6. If zero rows return, PostgreSQL has waited for any uncommitted conflicting identity:
   - in a new READ COMMITTED statement, join the committed idempotency row to Message;
   - compare fingerprint version, Event, and `jsonb` body;
   - return `409` on mismatch;
   - load and return the original Message on match;
   - do not resolve subscribers, run quota admission, or create fan-out.

The unique insert is the ownership/serialization point. The commit is the acceptance linearization point because that is when the authority and full result become visible atomically. `READ_COMMITTED` is explicit because the loser relies on a new statement snapshot after waiting; a future global isolation change must not silently invalidate the protocol.

### 7.3 No `IN_PROGRESS` state

A separately committed `IN_PROGRESS` record is unnecessary and harmful:

- committed before acceptance, it creates a second transaction boundary and needs stale-owner recovery;
- uncommitted in the acceptance transaction, it is invisible to normal readers and adds no information beyond the unique-index lock;
- crashes already cause PostgreSQL to roll back the provisional row and release waiters.

Waiters block at the unique insert until commit or rollback. If an upstream HTTP/database timeout ends a waiter, the client may retry the same key safely. Relay does not convert ordinary lock waiting into a customer-visible `IN_PROGRESS` result in P07.

## 8. PostgreSQL strategy evaluation

| Candidate | Linearization and blocking | Rollback/crash | Result replay and quota fit | Decision |
|---|---|---|---|---|
| Insert-first unique result row with `ON CONFLICT DO NOTHING` | Unique index serializes only identical scoped keys; loser waits for transaction outcome; acceptance visible at commit | Provisional row and all fan-out disappear automatically | Complete result ID is in the same transaction; duplicate path bypasses quota; ordinary FK/unique constraints | **Selected** |
| Unique fields directly on `messages` | Conflict also serializes, but Message insertion must happen before ownership is known; JPA constraint exceptions poison the transaction or require native Message upsert logic | Atomic if fully native, awkward through current entity flow | Couples key retention to Message schema and makes conflict/result handling invade Message persistence | Rejected |
| `SELECT ... FOR UPDATE` on an idempotency row | Locks an existing row, but cannot lock an absent identity; needs insert race handling or a broader sentinel lock | Safe only when combined with uniqueness | Adds reads/lock branches without replacing the unique authority | Rejected as incomplete/redundant |
| User/App sentinel row lock, then lookup/insert | Serializes all message keys for a User/App, not just equal keys | Transactional | Safe for quotas but harms independent admission concurrency and still needs result persistence | Rejected |
| Transaction-scoped advisory lock plus result table | Hash-derived lock serializes before row creation; collisions cause unrelated blocking; every writer must obey a non-relational protocol | Session/transaction locks release on crash | A unique result row is still needed for durable authority/replay; lock ordering becomes another global rule | Rejected |
| Separately committed `IN_PROGRESS` row | Early transaction is a visible linearization point separate from Message commit | Requires leases/stale takeover and can strand state | Cannot make fan-out and future usage one decision | Rejected |
| Redis `SETNX`/cache | External authority and separate failure/expiry boundary | Eviction/restore/process failures can reopen identity | Cannot join PostgreSQL Message/fan-out/quota transaction | Rejected |

### 8.1 Lock order and deadlocks

The new order for keyed admission is:

```text
App/Event authorization reads
→ message_idempotency unique-key acquisition
→ future user-scoped quota/admission row lock
→ new Message
→ new Delivery per subscriber
→ new Attempt #1 per Delivery
```

P04/P05 retry and replay paths do not touch `message_idempotency`, so they cannot form a reverse idempotency/Endpoint/Delivery lock cycle. Future message-admission writers must acquire idempotency before quota. Cleanup is deferred; any later cleanup protocol must define a compatible order.

## 9. Concurrency semantics

### 9.1 Same key, same fingerprint

- One request inserts the unique row and becomes provisional owner.
- Other requests block inside PostgreSQL; no process-local coordination is used.
- If the owner commits, waiters observe zero inserted rows, verify the fingerprint, and return the original Message.
- Exactly one Message, one fan-out, and one future accepted-usage unit exist.

### 9.2 Same key, different fingerprint

- Requests serialize on the same unique key even before the fingerprint is known to the loser.
- If the owner commits, the loser compares against the committed Message and returns `409`.
- If the owner rolls back, the waiting request inserts the now-free identity and its different request is evaluated as a new operation.

### 9.3 Different keys or Apps

Different keys in the same App and the same key in different Apps do not conflict in the idempotency index. Future user-scoped quota locking may serialize the short quota decision separately; that is not P07 deduplication.

## 10. Failure and crash matrix

| Failure point | Durable result | Retry with same key |
|---|---|---|
| Crash before acquisition insert | Nothing | Acquires normally |
| Crash after acquisition, before Message insert | PostgreSQL rolls back identity | Acquires normally |
| Crash after Message insert, during fan-out | Identity, Message, partial Deliveries/Attempts all roll back | Acquires and rebuilds full fan-out |
| Crash after all writes, before commit | Entire transaction rolls back | Acquires normally |
| Commit succeeds, process dies before/while responding | One committed authority, Message, fan-out, and future usage admission | Wait/read committed result and return original `201` |
| Duplicate while owner transaction is open | No second durable state; loser waits on unique transaction | Commit leads to replay; rollback lets loser acquire |
| Duplicate after commit | No writes on replay path | Return original `201` |
| Duplicate after rollback | No identity exists | Retry becomes owner |
| Different fingerprint while owner is open | Waits for owner outcome | Commit gives `409`; rollback permits normal acquisition/evaluation |
| No subscribers after provisional acquisition | Transaction rolls back identity | Later retry reevaluates subscribers |
| Future quota rejection | Transaction rolls back identity and usage reservation | Later retry reevaluates quota |
| Database connection lost with ambiguous commit | Client cannot know outcome | Same key resolves to replay if committed or ownership if rolled back |
| Cleanup races active request | No P07 cleanup exists | No race |
| Retry after retention expiry | P07 defines no expiry | Record still exists and replays |

## 11. Existing invariants

### 11.1 P04 execution fencing

P07 does not change claim, completion, reset, execution generation, or ready-publication state. New initial Attempts retain generation `0`, null claim time, and `CREATED` status. A replayed HTTP admission inserts no Attempt.

### 11.2 P05 allocation and replay sequencing

Initial fan-out remains the only creator of a new Delivery and Attempt #1 for a newly accepted Message. Automatic retries and manual replays continue using Endpoint-before-Delivery locking and the Delivery-scoped sequence. P07 neither acquires existing Delivery allocation locks nor changes the unique `(delivery_id, attempt_no)` constraint.

### 11.3 P00 test isolation

PostgreSQL-backed P07 tests use `SharedPostgresContainer` and explicit cleanup. Because `message_idempotency.message_id` references Message, cleanup order becomes:

```text
attempts → deliveries → message_idempotency → messages
```

Tests use unique fixture identities and latches/database lock observation. They do not enable background scheduling unless a test explicitly requires it.

### 11.4 P06 scheduler ownership

P07 adds no scheduler, recurring callback, executor, or cleanup task. If a later retention project adds cleanup, it must use P06's scheduler classification/admission model rather than creating an unqualified scheduler.

## 12. Future quota and accounting extension

The new-operation branch exposes one exact extension point after idempotency ownership and subscriber resolution, but before Message/fan-out commit:

```text
if duplicate committed identity:
    return original result                 # no quota path

resolve subscribers
futureAdmission.acceptNewMessage(
    userId,
    environmentId,
    appId,
    messageId,
    acceptedAt,
    payloadBytes,
    fanoutCount
)
persist Message + Deliveries + Attempts
commit
```

The future hook must use the existing transaction with mandatory propagation. It may lock/update a user-period quota row and insert a usage fact uniquely keyed by `message_id` or the idempotency result identity. It must not open `REQUIRES_NEW` work.

P07 persists now:

- stable user ID;
- target App (and therefore Environment);
- accepted Message ID;
- database-authoritative acceptance timestamp;
- operation-specific idempotency identity.

That is enough to ensure:

- an idempotent replay returns before quota evaluation;
- a newly rejected/rolled-back request consumes neither identity nor usage;
- one committed Message can own at most one future accepted-message usage fact;
- keyless accepted requests still traverse the same future new-admission hook and consume one unit each.

Policy revision, period ID, payload-byte measurement, and usage tables belong to T1–T3 and are not fabricated in P07.

## 13. Retention and cleanup

P07 has no expiry column and no cleanup job. A committed key is not reusable.

This is the only safe interim contract because Relay has no chosen Message/payload retention promise and no existing cleanup authority. Selecting an arbitrary 24-hour or 30-day window would create a customer-visible duplicate window without product evidence.

A later retention design may add expiry only if it defines all of the following together:

- advertised minimum idempotency horizon;
- whether expiry permits a new Message for the same key;
- relationship to Message/payload retention and active Deliveries;
- preservation of a compact fingerprint/result if Message content is removed;
- bounded batches and P06 scheduler ownership;
- PostgreSQL-time cutoffs;
- row-lock/delete behavior for a retry racing expiry;
- metrics/audit for expired identities.

Uncommitted rows are invisible to cleanup and protected by their transaction. For committed rows, deletion is itself the point after which duplication becomes legal; it must never be introduced as an undocumented storage optimization.

## 14. Deterministic verification strategy

All correctness tests use PostgreSQL 16 through the shared Testcontainers fixture. No concurrency assertion depends on `Thread.sleep`.

| Case | Deterministic mechanism and assertions |
|---|---|
| First keyed acceptance | Actual service; assert one identity, Message, expected Deliveries, and Attempt #1 rows |
| Keyless submissions | Submit identical DTO twice without key; assert two independent Message/fan-outs and zero idempotency rows |
| Sequential keyed retry | Submit twice; assert same Message response and unchanged cardinality |
| Response-loss equivalent | Commit first call and discard result; retry on a new call/transaction; assert original Message/cardinality |
| Concurrent same fingerprint | Gate owner immediately after acquisition; start second connection; observe second blocked through `pg_blocking_pids`/`pg_stat_activity`; release owner; assert both results name one Message and one fan-out |
| Concurrent conflicting fingerprint | Same gate; owner commits A; loser B returns `409`; cardinality remains one |
| Rollback then retry | Inject fan-out persistence failure after acquisition; assert zero identity/Message/Delivery/Attempt; remove fault and retry successfully |
| No subscribers | Actual service returns `422`; assert no identity or Message; add subscriber and retry same key successfully |
| Cross-App scope | Same User/key and equivalent DTO against two Apps; assert two Messages and two identity rows |
| Same User, different credentials | Two principals/credential fixtures resolve to the same user UUID; assert convergence; email/credential value does not enter repository key |
| Fingerprint semantics | Object property order/whitespace and equivalent PostgreSQL numerics replay; Event change, array order change, and JSON type change conflict |
| Actual protocol isolation | At the repository boundary inside each real service transaction, use the transaction-bound JDBC connection to execute `SHOW transaction_isolation`; assert `read committed` for winner and loser and associate the observation with their backend PIDs |
| Transaction visibility | Observe loser waiting on winner transaction ID; winner commit makes the loser's insert return no row and its next statement see/reuse the committed authority; winner rollback lets the loser acquire and leaves no orphan authority |
| Authority coherence | After real acceptance, join authority → Message → App → Environment and assert `i.app_id = m.app_id` and `i.user_id = e.user_id`; fully scoped Message lookup must resolve the same Message |
| Constraint-boundary corruption | Without disabling constraints, use valid rows to insert a cross-wired authority in a rollback-scoped test; prove individual FKs allow it, the audit query detects it, and replay fails loudly rather than creating or repairing a Message |
| No expiry | Seed/age an identity; retry still replays; catalog asserts no expiry/cleanup contract in V15 |
| Cardinality | For N subscribers: exactly 1 Message, N Deliveries, N Attempt #1 rows after any number of same-key retries |
| P04/P05 preservation | Assert initial Attempt generation/claim/status and run fencing, replay allocation, Delivery status, and Message transaction suites |
| P00/P06 preservation | Run background-context policy and scheduler-topology regressions; assert P07 adds no scheduled method |

Timeouts bound a failed test but are not used to create ordering. Latches and observed database waits create the required interleavings.

## 15. Migration and rollout

### 15.1 Additive migration

V15 creates an empty table. Historical Messages have no client-provided key and are not backfilled or guessed. Existing Message, Delivery, and Attempt rows are unchanged.

The primary key, Message uniqueness constraint, key-length/version checks, and deferred Message FK are created with the table. The table is empty, so no historical uniqueness audit or large index build is required.

Migration tests must prove:

- V1–V14 data survives byte-for-byte/count-equivalent;
- old-style Message/fan-out inserts still succeed without an idempotency row;
- a committed idempotency row cannot reference a missing Message;
- each `user_id`, `app_id`, and `message_id` foreign key independently rejects a missing target;
- the FK is deferrable and succeeds when identity precedes Message in one transaction;
- duplicate scoped keys and duplicate result Message IDs fail.

Catalog assertions must not overstate the schema: V15 contains no composite ownership/result FK. Deployment audits therefore include the coherence join described in Section 6.1 and expect zero mismatches.

### 15.2 Mixed-version behavior

Schema-first deployment is structurally compatible with old binaries because they do not touch the new table. It is **not correctness-safe to advertise keyed idempotency while any old binary can receive message-ingestion traffic**: an old instance ignores the header and can create an untracked duplicate.

Required rollout:

1. Apply V15.
2. Deploy P07 binaries without advertising the contract externally.
3. Ensure all old binaries are drained from message-ingestion traffic.
4. Run a keyed sequential/concurrent smoke test and cardinality audit.
5. Advertise/enable client use of the header.

Blue/green or ingress routing that sends keyed requests only to new binaries is also safe. A normal mixed rolling fleet without such routing is not.

### 15.3 Rollback and roll-forward

- Before clients can use the feature, application rollback is safe; leave the additive table in place.
- After the contract is exposed, rolling back to a binary that ignores the header is unsafe. Roll forward.
- V15 should not be dropped during application rollback; dropping it discards accepted identities and immediately reopens duplication.
- If V15 itself is incorrect before exposure, restore/repair under the normal migration procedure. No automatic down migration is supplied.

## 16. Implementation boundaries

Expected production units:

- API value/validation for the optional header;
- `InvalidIdempotencyKeyException` (`400`) and `IdempotencyConflictException` (`409`);
- JDBC-backed `MessageIdempotencyRepository` implementing the exact insert/conflict/read protocol;
- a small immutable record for acquired/existing result metadata;
- an explicit-ID/accepted-time Message construction path;
- `MessageService` orchestration that separates keyless, new-key owner, and committed-replay branches;
- V15 migration.

The implementation does not add Redis access, schedulers, committed progress states, generic idempotency middleware, full HTTP response storage, entitlement checks, or usage tables.

## 17. Resolved product semantics and remaining decisions

Resolved for P07:

- optional header on the existing endpoint;
- 1–255-byte RFC-token syntax, case-sensitive;
- User + App + operation scope, Environment transitive through App;
- Event plus `jsonb` body fingerprint;
- `409` conflict on mismatched reuse;
- reconstructed original `201` domain response;
- no durable record for failed acceptance;
- PostgreSQL blocking convergence for concurrent duplicates;
- no expiry/cleanup in P07;
- no tier gating;
- coordinated rollout before advertising support.

No unresolved owner/product decision blocks P07 implementation. Future decisions—mandatory keys in another API version, a finite retention promise, quota policy, response replay indicator, and idempotency for manual replay—are explicitly outside this project. A future multi-user/Account/Organization/Workspace ownership project does carry a migration obligation: it must migrate Apps and their existing idempotency identities to one coherent durable owner and keep credentials separate from that owner identity. That obligation requires no speculative P07 schema change today.

## 18. Acceptance criteria

1. Requests without `Idempotency-Key` retain independent-submission semantics.
2. A valid supplied key is authoritative in PostgreSQL and independent of credential type or plan.
3. Sequential, response-loss, and concurrent same-fingerprint retries converge on one Message and one fan-out.
4. Different fingerprint reuse returns deterministic `409` and no writes.
5. Rollback/no-subscriber/future-quota rejection leaves no identity or usage admission.
6. Commit plus process death is recoverable from the same key.
7. The authority, Message, Deliveries, Attempts, and future usage hook share one transaction.
8. No durable `IN_PROGRESS` state, Redis authority, automatic payload dedupe, expiry, cleanup scheduler, or tier check is introduced.
9. P04 execution fencing, P05 allocation/lock order, P00 isolation, and P06 scheduler ownership remain intact.
10. Migration and deployment documentation explicitly prohibit advertising the guarantee to an uncoordinated mixed old/new fleet.
11. A real PostgreSQL test observes `read committed` inside the actual acquisition transaction and proves both winner-commit replay and winner-rollback takeover without sleeps.
12. PostgreSQL-backed tests prove every protocol-created authority has coherent User/App/Message ownership, while a corruption-oriented test documents the narrower boundary of the database constraints and preserves fail-loud replay behavior.

## 19. Implementation verification (2026-10-07; Task 8 review correction)

P07 production implementation is present at code revision `c2f436d1db76b36eebc9b68c6d350f802d85fdd6`. The additive V15 migration, service protocol, optional header, and regression tests implement the contract above without changing it.

Verification evidence:

- The complete P07 selection passed: 42 tests, 0 failures, 0 errors, 0 skipped. The P04/P05 regression selection passed 49 tests, and the P00/P06 selection passed 18 tests, all with zero failures/errors/skips. Exact commands and class lists are preserved in the implementation report and task evidence.
- The full `./mvnw test` suite was started with the repository's inert test-only JWT/email variables but did not complete. It reached `BrevoEmailSenderTest.send_throwsEmailSendException_on429RateLimited`, where the main test thread remained blocked reading an automatic HttpClient retry response from MockWebServer after its test queued one 429 response. After more than two minutes without new Surefire reports, the run was interrupted (exit 130). The 169 completed class reports contain 877 tests, all with zero failures/errors/skips. This is a pre-existing non-P07 email-test hang; full-suite success is not claimed.
- Repository-wide `spotless:check` fails because 201 Java files require formatting. Its output names `AttemptMapperTest.java` and `AttemptExecutionRepositoryPostgresTest.java`, then summarizes 199 other files. The first documented P07-scoped selector was incorrect and selected zero files; it is not evidence of a pass. For the review correction, the selector was generated from the 21 Java files changed since base `761b539d66b14fb09ceff150fc4ccb60e855170b` (including `GlobalExceptionHandler.java`): `p07_files_regex=$(git diff --name-only 761b539d -- '*.java' | sed 's/[.]/[.]/g' | sed 's|^|.*/|' | paste -sd '|' -)`. Scoped `spotless:apply` reported 21 selected, 12 changed to clean and 9 cache-skipped; scoped `spotless:check` with the identical selector reported 21 clean, 0 needing changes, and 21 cache-skipped. The post-format P07 regression selection passed 46 tests with zero failures/errors/skips.
- The actual migrated PostgreSQL 16 catalog contains seven constraints and two indexes on `message_idempotency`; the Message FK is `DEFERRABLE INITIALLY DEFERRED`. The rollout coherence query returned zero mismatches. On the local smoke graph, both `user_id = App owner` and `app_id = Message app` evaluated true.
- The actual service concurrency tests report `SHOW transaction_isolation = read committed` for owner and contender on distinct backend PIDs. PostgreSQL observed the contender waiting on the owner's transaction ID lock. Winner commit caused the loser to reuse the authority; winner rollback let the loser acquire it.
- A local disposable two-instance HTTP smoke used the same P07 jar against one PostgreSQL 16 database. Instance 1 accepted a keyed request with `201`; instance 2 replayed `201` with the same Message ID; changed content returned `409`; keyless identical submissions through the two instances returned `201` with distinct IDs. Stable post-smoke cardinality was one authority, three Messages, three Deliveries, and three Attempt #1 rows. All temporary application processes and containers were removed.

The local smoke is not a coordinated deployment target. Before advertising the header in production, apply V15, route all keyed ingestion to P07 binaries or drain all older binaries from ingestion, then run the coordinated rollout smoke and the catalog/coherence audit. Application rollback to a key-ignorant binary remains unsafe after clients rely on the contract; leave V15 installed and roll forward.
