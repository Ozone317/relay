# P05 final verification and rollout record

Date: 2026-10-05. Task 6 base: `f203577`; P05 branch base: `a7148d7`.
Task 6 is complete with the owner-authorized forward V14 correction. The final focused selection passed 79 tests,
supplemental native/P04/lifecycle/service gates passed 56, and the practical Docker full suite passed 856. One
confirmed unrelated Brevo 429 method was excluded; the other 10 Brevo tests passed. Global formatting still reports
179 baseline violations; both changed Java files and diff checks pass. No external deployment DB was audited,
and no merge/deployment was performed.

## V14 correction and migration preservation

Task 1 proved that a single-Delivery `ORDER BY attempt_no DESC LIMIT 1` query uses the ascending unique index backward,
without Sort. That evidence was incomplete for the existing `delivery_status` mixed ordering
`delivery_id ASC, attempt_no DESC` and pageable count. The first Task 6 probe found median list/count times of
174.729/81.846 ms without the DESC index versus 136.423/33.024 ms with it (5,000 Deliveries/100,000 Attempts).
The count gained an Incremental Sort over all Attempt rows. Task 6 stopped before a completion commit; the owner
then authorized only this forward correction:

```sql
-- V13's unique Delivery/ordinal index is the correctness backstop. This mixed-order
-- index serves the existing delivery_status list/count read pattern, not uniqueness.
CREATE INDEX idx_attempts_delivery_attempt_no
    ON attempts (delivery_id, attempt_no DESC);
```

File: `V14__restore_attempt_delivery_ordering_index.sql`. V13 is unchanged; its Git blob hash remains
`6edc15df37911af68dce5c151d8b37c17e397734`.
Final index purposes differ: V13's ascending unique index backs physical ordinal uniqueness; V14's non-unique
mixed-order index serves existing list/count reads. Shared columns do not make those orderings/purposes redundant.

TDD and compatibility commands, with the required test environment defined below:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -Dtest=AttemptSequenceMigrationPostgresTest -Drelay.retry.scheduling-enabled=false test
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -Dtest=AttemptSequenceMigrationPostgresTest,AttemptExecutionMigrationLifecyclePostgresTest -Drelay.retry.scheduling-enabled=false test
```

RED before V14: 5 tests, 1 failure, 0 errors/skips; new
`v14RestoresMixedOrderIndexWithoutMutatingAttemptHistory` failed at the restored-index existence assertion.
GREEN after V14: 6 tests, 0 failures/errors/skips, BUILD SUCCESS.
The new real Flyway test snapshots three full `row_to_json(a)::text` rows ordered by ID on V12, then checks exact
serialized byte equality after V13 and V14. Seeded history includes FAILED_RETRYING/DEAD/SCHEDULED, non-null diagnostics,
response/latency, positive historical terminal generations, and next_retry_at. Both named permanent constraints and
indexes survive, the restored definition ends with `USING btree (delivery_id, attempt_no DESC)`, and direct duplicate/
non-positive inserts still fail with SQLStates 23505/23514. Failed inserts also preserve the complete history.
No Attempt row is lost, renumbered, or mutated.

The existing V13 valid-history/latest-plan test is now explicitly pinned to target 13, preserving its historical
old-index-absent assertion and unique-index backward-scan checkpoint. Invalid historical shapes continue to fail V13
before V14; their rollback assertions remain unchanged. P04 migration/recovery behavior passes through latest V14.

## Positive creator and lock audit

Exact required inventory command, exit 0:

```bash
rg -n --hidden --glob '!target/**' --glob '!.git/**' --glob '!.worktrees/**' \
  'new Attempt\(|AttemptRepository\.save|attemptRepository\.(save|saveAll|saveAndFlush)|INSERT INTO attempts|attempt_no|nextAttemptNo' \
  src/main/java src/main/resources/db/migration
```

All results:

```text
src/main/resources/db/migration/V14__restore_attempt_delivery_ordering_index.sql:3:CREATE INDEX idx_attempts_delivery_attempt_no
src/main/resources/db/migration/V14__restore_attempt_delivery_ordering_index.sql:4:    ON attempts (delivery_id, attempt_no DESC);
src/main/resources/db/migration/V1__baseline.sql:86:    attempt_no     integer                  NOT NULL,
src/main/resources/db/migration/V13__enforce_attempt_delivery_sequence.sql:6:        SELECT 1 FROM attempts GROUP BY delivery_id, attempt_no HAVING COUNT(*) > 1
src/main/resources/db/migration/V13__enforce_attempt_delivery_sequence.sql:8:        RAISE EXCEPTION 'P05 migration blocked: duplicate (delivery_id, attempt_no); run the documented duplicate audit';
src/main/resources/db/migration/V13__enforce_attempt_delivery_sequence.sql:11:    IF EXISTS (SELECT 1 FROM attempts WHERE attempt_no < 1) THEN
src/main/resources/db/migration/V13__enforce_attempt_delivery_sequence.sql:12:        RAISE EXCEPTION 'P05 migration blocked: non-positive attempt_no; run the documented history audit';
src/main/resources/db/migration/V13__enforce_attempt_delivery_sequence.sql:19:        HAVING MIN(attempt_no) <> 1 OR MAX(attempt_no) <> COUNT(*)
src/main/resources/db/migration/V13__enforce_attempt_delivery_sequence.sql:26:    ADD CONSTRAINT attempts_attempt_no_positive CHECK (attempt_no >= 1),
src/main/resources/db/migration/V13__enforce_attempt_delivery_sequence.sql:27:    ADD CONSTRAINT uk_attempts_delivery_attempt_no UNIQUE (delivery_id, attempt_no);
src/main/resources/db/migration/V13__enforce_attempt_delivery_sequence.sql:30:DROP INDEX idx_attempts_delivery_attempt_no;
src/main/resources/db/migration/V5__add_deliveries_and_delivery_status_view.sql:39:-- ordered by attempt_no, not created_at - see spec Section 5.1 for why (this project's own
src/main/resources/db/migration/V5__add_deliveries_and_delivery_status_view.sql:41:CREATE INDEX idx_attempts_delivery_attempt_no ON attempts(delivery_id, attempt_no DESC);
src/main/resources/db/migration/V5__add_deliveries_and_delivery_status_view.sql:60:    a.attempt_no                               AS attempt_no,
src/main/resources/db/migration/V5__add_deliveries_and_delivery_status_view.sql:71:ORDER BY a.delivery_id, a.attempt_no DESC;
src/main/java/com/example/relay/attempt/domain/Attempt.java:54:    @Column(name = "attempt_no", nullable = false, updatable = false)
src/main/java/com/example/relay/delivery/domain/DeliveryStatus.java:18: * Delivery, already joined to its highest-attempt_no Attempt. Never written to directly - a
src/main/java/com/example/relay/delivery/domain/DeliveryStatus.java:61:    @Column(name = "attempt_no")
src/main/java/com/example/relay/attempt/application/AttemptService.java:57:        List<Attempt> createdAttempts = attemptRepository.saveAll(attempts);
src/main/java/com/example/relay/attempt/application/AttemptService.java:73:            Attempt attempt = new Attempt(sub.getApp(), message, sub.getEndpoint(), delivery, 1);
src/main/java/com/example/relay/attempt/application/AttemptService.java:111:        int attemptNo = allocationRepository.nextAttemptNoUnderDeliveryLock(delivery.getId());
src/main/java/com/example/relay/attempt/application/AttemptService.java:113:            Attempt replay = attemptRepository.saveAndFlush(
src/main/java/com/example/relay/attempt/application/AttemptService.java:114:                    new Attempt(delivery.getApp(), delivery.getMessage(), delivery.getEndpoint(), delivery, attemptNo));
src/main/java/com/example/relay/attempt/application/AttemptService.java:155:        int nextAttemptNo = allocationRepository.nextAttemptNoUnderDeliveryLock(previous.getDelivery().getId());
src/main/java/com/example/relay/attempt/application/AttemptService.java:156:        Attempt retry = new Attempt(previous.getApp(), previous.getMessage(), previous.getEndpoint(),
src/main/java/com/example/relay/attempt/application/AttemptService.java:157:                previous.getDelivery(), nextAttemptNo);
src/main/java/com/example/relay/attempt/application/AttemptService.java:161:            attemptRepository.saveAndFlush(retry);
src/main/java/com/example/relay/attempt/application/AttemptService.java:163:            recordInvariantViolation("retry", previous.getDelivery().getId(), nextAttemptNo, exception);
src/main/java/com/example/relay/attempt/infrastructure/AttemptAllocationRepositoryImpl.java:44:    public int nextAttemptNoUnderDeliveryLock(UUID deliveryId) {
src/main/java/com/example/relay/attempt/infrastructure/AttemptAllocationRepositoryImpl.java:46:                SELECT COALESCE(MAX(attempt_no), 0) + 1 FROM attempts WHERE delivery_id = :deliveryId
src/main/java/com/example/relay/attempt/infrastructure/AttemptAllocationRepository.java:28:    int nextAttemptNoUnderDeliveryLock(UUID deliveryId);
src/main/java/com/example/relay/deliveryengine/worker/DeliveryWorker.java:121:            int nextAttemptNo = attempt.getAttemptNo() + 1;
src/main/java/com/example/relay/deliveryengine/worker/DeliveryWorker.java:122:            RetryTier tier = RetryTier.forAttemptNo(nextAttemptNo);
```

Classification of every matching result (line numbers at audited revision):

| Source and lines | Classification and result |
|---|---|
| AttemptService:57,73 | initial fan-out: new Delivery, fixed Attempt 1, same Message transaction |
| AttemptService:111,113–114 | manual replay: Endpoint SHARE -> active -> Delivery UPDATE -> current eligibility -> current max+1 CREATED insert |
| AttemptService:155–157,161,163 | automatic retry: Endpoint KEY SHARE -> Delivery UPDATE -> P04 parent fence -> current max+1 SCHEDULED insert; line 163 only logs and rethrows invariant failures |
| AttemptAllocationRepository:28; AttemptAllocationRepositoryImpl:44,46 | shared retry/manual-replay allocation contract and current-max query; both creators call only after same-transaction ordered parent locks |
| DeliveryWorker:121–122 | automatic retry policy/tier calculation only; detached number is not passed as the persisted child ordinal |
| Attempt:54 | schema/mapping only: immutable ordinal column; no setter or allocator |
| DeliveryStatus:18,61 | schema/view mapping only, read-only presentation consumer |
| V1:86; V5:39,41,60,71; V13:6,8,11,12,19,26,27,30; V14:3–4 | schema only: Flyway DDL/audit, index definition/removal/restoration, view ordering; no production Attempt insertion |

Supplementary positive searches, all exit 0, inspected against implementation:

```bash
rg -n -i 'new Endpoint\(|endpointRepository\.(save|saveAll|saveAndFlush|delete|deleteAll|deleteBy)|insert into endpoints|update endpoints|delete from endpoints|new Delivery\(|deliveryRepository\.(save|saveAll|saveAndFlush|delete)|insert into deliveries|update deliveries|delete from deliveries|update attempts|insert into attempts|setAttemptNo|createRetry|createReplay|createFromSubscriptionList|markFailedAndCreateRetry|FOR (UPDATE|SHARE|KEY SHARE)' src/main/java src/main/resources/db/migration
rg -n 'CASCADE|REFERENCES endpoints|REFERENCES deliveries|references endpoints|references deliveries|cascade\s*=|\.setActive\(|\.setUrl\(|\.setName\(|\.persist\(|\.merge\(|\.remove\(' src/main/java src/main/resources/db/migration
rg -n -i 'AttemptRepository|EndpointRepository|DeliveryRepository|EntityManager|JdbcTemplate|createNativeQuery|createQuery|\.setStatus\(|\.setAttemptNo\(|execution_generation\s*=' src/main/java
```

Findings: every Attempt constructor/save is in AttemptService. No SQL production insert, EntityManager insert,
alternative repository writer, ordinal setter, or fourth creator was found. EndpointService is the only Endpoint writer;
EndpointMapper constructs its new entity. No JPA cascade writes Endpoint or Delivery. Remaining Attempt UPDATE paths
only mutate an existing row: AttemptExecutionRepositoryImpl (claim, fenced success/failure, reconciliation reset);
ReadyWorkRepositoryImpl (scheduled promotion, ready dispatch lease/publication); AttemptRepository (dead-letter touch/
notification). RetryScheduler, ReconciliationSweeper, DeliveryWorker, and DeadLetterNotifier delegate to those paths.
V5 backfills Deliveries/Attempt delivery links; V12 backfills the P04 claim timestamp. Neither creates historical Attempts.

### Endpoint/FK writer inventory

| Writer | Transaction boundary and mechanism | Lock/FK behavior | Delivery touched in transaction? |
|---|---|---|---|
| EndpointService.create:35–51 via EndpointMapper:16–17 | No service transaction annotation; inherited Spring Data saveAndFlush owns transaction, JPA INSERT | New Endpoint row; App FK takes parent KEY SHARE; unique app/name check | No |
| EndpointService.update:63–83, including active/deactivate/reactivate:73–74 | No service transaction annotation; inherited save owns transaction, merge/dirty-check UPDATE | Ordinary non-key activity change takes NO KEY UPDATE, incompatible with replay SHARE but compatible with retry KEY SHARE; actual key/name changes can take stronger UPDATE. Existing App FK remains bound | No |
| EndpointService.delete:86–88 | No service transaction annotation; inherited delete owns transaction, JPA DELETE | Endpoint UPDATE-strength delete lock first; NO ACTION FK examination of Subscription, Attempt, Delivery may wait or reject | May examine referencing Delivery rows through FK after Endpoint; no explicit Delivery mutation |
| SubscriptionService.create/delete | Repository-owned transaction; JPA Subscription insert/delete | Insert performs Endpoint FK KEY SHARE; delete does not append work | No |
| MessageService.create -> AttemptService.createFromSubscriptionList | Outer MessageService @Transactional, participating AttemptService transaction | Message insert, new Delivery insert (Endpoint FK KEY SHARE), fixed Attempt 1 insert (Endpoint and Delivery FK KEY SHARE); all commit or roll back together | New uncommitted Delivery only; no existing Delivery lock before Endpoint |
| AttemptService.createReplay | One proxied @Transactional service method; mandatory allocation repository operations | Endpoint SHARE and activity read -> matching Delivery UPDATE -> latest DEAD/active guard -> max+1 -> saveAndFlush CREATED -> commit; both locks retained | Yes, ordered Endpoint first |
| AttemptService.markFailedAndCreateRetry | One proxied @Transactional service method; mandatory allocation repository operations | Endpoint KEY SHARE -> matching Delivery UPDATE -> conditional P04 IN_FLIGHT/exact positive generation parent UPDATE -> max+1 -> SCHEDULED child -> commit | Yes, ordered Endpoint first |
| P04 completion/reconciliation/ready/dead-letter | Service/repository transaction or single JDBC statement; existing-row UPDATE only | Existing Attempt row lock; immutable parent IDs/ordinal; no Delivery allocation lock followed by Endpoint acquisition | No |

Missing Endpoint/Delivery rows and Endpoint/Delivery mismatch fail loudly; inactive replay returns false before Delivery
lock. All three allocation operations have Propagation.MANDATORY. There are exactly two production max+1 call sites,
both inside the matching service transaction after its ordered lock operation. No reverse Delivery-before-Endpoint edge
was found. Replay's ownership lookup reads immutable ownership only; no earlier eligibility snapshot authorizes insertion.

P04 remains the sole production path setting IN_FLIGHT or incrementing execution generation. Retry parent completion
still predicates on Attempt ID, IN_FLIGHT, exact generation, generation > 0; zero-row completion returns OWNERSHIP_LOST
before max+1. New Attempt constructor uses CREATED, generation 0, null claim time; retry sets SCHEDULED/nextRetryAt only.
Invariant exception catches record bounded metrics/log metadata and rethrow the original exception; no V13 violation is
translated into normal concurrency.


## Schema/preflight and final inventory

Disposable PostgreSQL 16.15 database only: container `relay-p05-task6-postgres`, image `postgres:16`.
Representative schema executes exact repository migration SQL; real Flyway chain/checks are separately exercised by
the migration tests. Initial setup applied V1–V13 in version order, then seeded five Apps/Endpoints, 5,000 Deliveries,
and 100,000 contiguous terminal Attempts. V14 was applied from its actual source file:

```bash
docker run --detach --name relay-p05-task6-postgres -e POSTGRES_USER=p05 -e POSTGRES_PASSWORD=p05 -e POSTGRES_DB=p05 postgres:16
for migration in $(rg --files src/main/resources/db/migration | sort -V); do
  docker exec -i relay-p05-task6-postgres psql -X -v ON_ERROR_STOP=1 -U p05 -d p05 < "$migration" || exit 1
done
docker exec -i relay-p05-task6-postgres psql -X -v ON_ERROR_STOP=1 -U p05 -d p05 < target/task-6-seed.sql
docker exec -i relay-p05-task6-postgres psql -X -v ON_ERROR_STOP=1 -U p05 -d p05 < src/main/resources/db/migration/V14__restore_attempt_delivery_ordering_index.sql
```

At the initial setup command V14 did not yet exist; rerunning the setup from final source applies V1–V14 and must omit
the separate V14 application. Seed/audit SQL:

```sql
\set ON_ERROR_STOP on
INSERT INTO users(id,email,password,email_verified) VALUES ('00000000-0000-0000-0000-000000000001','p05-task6@example.com','hash',true);
INSERT INTO environments(id,user_id,name,created_at,updated_at) VALUES ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','env',now(),now());
INSERT INTO apps(id,name,environment_id,created_at) SELECT ('00000000-0000-0000-0000-' || lpad(n::text,12,'0'))::uuid, 'app-' || n, '00000000-0000-0000-0000-000000000002', now() FROM generate_series(3,7) n;
INSERT INTO events(id,name,app_id,created_at) SELECT gen_random_uuid(),'event',id,now() FROM apps;
INSERT INTO endpoints(id,name,url,signing_secret,is_active,app_id,created_at,updated_at) SELECT gen_random_uuid(),'endpoint','https://example.com','secret',true,id,now(),now() FROM apps;
INSERT INTO messages(id,app_id,event_id,body,created_at) SELECT gen_random_uuid(),a.id,e.id,'{}',now() - (n * interval '1 second') FROM apps a JOIN events e ON e.app_id=a.id CROSS JOIN generate_series(1,1000) n;
INSERT INTO deliveries(id,app_id,message_id,endpoint_id,created_at) SELECT gen_random_uuid(),m.app_id,m.id,e.id,m.created_at FROM messages m JOIN endpoints e ON e.app_id=m.app_id;
INSERT INTO attempts(id,app_id,message_id,endpoint_id,delivery_id,attempt_no,status,created_at,updated_at) SELECT gen_random_uuid(),d.app_id,d.message_id,d.endpoint_id,d.id,n,'DEAD',d.created_at + (n * interval '1 millisecond'),now() FROM deliveries d CROSS JOIN generate_series(1,20) n;
ANALYZE;
SELECT version();
SELECT (SELECT count(*) FROM apps) AS apps,(SELECT count(*) FROM deliveries) AS deliveries,(SELECT count(*) FROM attempts) AS attempts;
-- Exact design preflight queries.
SELECT delivery_id, attempt_no, COUNT(*) AS row_count, array_agg(id ORDER BY created_at, id) AS attempt_ids
FROM attempts GROUP BY delivery_id, attempt_no HAVING COUNT(*) > 1;
SELECT delivery_id, MIN(attempt_no), MAX(attempt_no), COUNT(*)
FROM attempts GROUP BY delivery_id
HAVING MIN(attempt_no) <> 1 OR MAX(attempt_no) <> COUNT(*) OR BOOL_OR(attempt_no < 1);
SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'attempts'::regclass AND conname IN ('attempts_attempt_no_positive', 'uk_attempts_delivery_attempt_no');
SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = current_schema() AND tablename = 'attempts' AND indexname IN ('idx_attempts_delivery_attempt_no', 'uk_attempts_delivery_attempt_no');
SELECT conrelid::regclass AS child, conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE contype='f' AND confrelid IN ('endpoints'::regclass,'deliveries'::regclass) ORDER BY 1,2;
```

Both exact design preflight queries were rerun after V14 against non-empty representative data, returning zero rows.
Final catalog/digest evidence (the same constraint/index queries as Task 6, plus the exact design preflight):

```text
             conname             |       pg_get_constraintdef
---------------------------------+----------------------------------
 uk_attempts_delivery_attempt_no | UNIQUE (delivery_id, attempt_no)
 attempts_attempt_no_positive    | CHECK ((attempt_no >= 1))
(2 rows)

            indexname             |                                                   indexdef
----------------------------------+--------------------------------------------------------------------------------------------------------------
 uk_attempts_delivery_attempt_no  | CREATE UNIQUE INDEX uk_attempts_delivery_attempt_no ON public.attempts USING btree (delivery_id, attempt_no)
 idx_attempts_delivery_attempt_no | CREATE INDEX idx_attempts_delivery_attempt_no ON public.attempts USING btree (delivery_id, attempt_no DESC)
(2 rows)

 delivery_id | attempt_no | row_count | attempt_ids
-------------+------------+-----------+-------------
(0 rows)

 delivery_id | min | max | count
-------------+-----+-----+-------
(0 rows)

 count  |      serialized_history_md5
--------+----------------------------------
 100000 | d06f829afbd4ff2dd12b6c8428c38a02
(1 row)
```

The 100,000-row representative history digest was checked before and after applying V14:

```sql
SELECT count(*), md5(string_agg(row_to_json(a)::text, chr(10) ORDER BY id)) FROM attempts a;
```

Both returned `100000 | d06f829afbd4ff2dd12b6c8428c38a02`, including after the final disposable comparison.
The permanent positivity check and unique physical ordinal constraint remain, as does the separate active-row index.
Contiguity is proved historically at migration only. PostgreSQL does not permanently enforce 1..N; ordered parent locks,
current max+1, same-transaction insertion, and rollback maintain committed gaplessness afterward.

Deployment operators must export these same duplicate/contiguity queries on the actual target DB before V13. Non-empty
output blocks migration and requires an explicit data-owner repair decision with preserved forensic evidence. This
repository session did not inspect production/external data.

## PostgreSQL 16 query plans and representative timings

Task 1's original pre-drop report records PostgreSQL 16.15, 101 Deliveries/2,002 Attempts, a real seeded Delivery,
`Index Scan Backward using uk_attempts_delivery_attempt_no`, no Sort, 0.017 ms. The pinned V13 test repeats that
historical access path in the final focused run:

```text
P05 PostgreSQL 16 latest-Attempt plan (2,002 attempts; 101 deliveries; old index dropped):
Limit  (cost=0.28..3.69 rows=1 width=1229) (actual time=0.013..0.014 rows=1 loops=1)
  Buffers: shared hit=3
  ->  Index Scan Backward using uk_attempts_delivery_attempt_no on attempts  (cost=0.28..68.62 rows=20 width=1229) (actual time=0.012..0.013 rows=1 loops=1)
        Index Cond: (delivery_id = '0159ed11-e9c2-40e6-a0a7-992b33fced5e'::uuid)
        Buffers: shared hit=3
Planning:
  Buffers: shared hit=114
Planning Time: 0.361 ms
Execution Time: 0.031 ms
2026-10-05T10:27:10.579+05:30  INFO 28489 --- [relay] [           main] org.flywaydb.core.FlywayExecutor         : Database: jdbc:postgresql://localhost:32789/test?loggerLevel=OFF (PostgreSQL 16.15)
```

The actual list shape is `DeliveryQueryService.getPage`: app authorization query first, then an independent
`DeliveryStatusSpecifications.matching(appId,...)` view select with default `deliveryCreatedAt DESC` sort.
The measured list and pageable count use that exact app-only shape (20-row page, 1,000 matching Deliveries).
No forced index/disabled planner switches and no wall-clock assertions were added.

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT * FROM delivery_status
WHERE app_id='00000000-0000-0000-0000-000000000003'
ORDER BY delivery_created_at DESC LIMIT 20;

EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT count(*) FROM delivery_status
WHERE app_id='00000000-0000-0000-0000-000000000003';
```

After all regression JVMs completed, ran five warm before/after comparisons in the disposable database.
The controlled pre-V14 shape temporarily drops only its read index; the correction is applied using actual V14 SQL:

```bash
docker exec relay-p05-task6-postgres psql -X -v ON_ERROR_STOP=1 -U p05 -d p05 -c 'DROP INDEX idx_attempts_delivery_attempt_no;'
for run in 1 2 3 4 5; do
  docker exec -i relay-p05-task6-postgres psql -X -v ON_ERROR_STOP=1 -U p05 -d p05 < target/task-6-repeat-plans.sql
done > target/task-6-v14-steady-before.log 2>&1
docker exec -i relay-p05-task6-postgres psql -X -v ON_ERROR_STOP=1 -U p05 -d p05 < src/main/resources/db/migration/V14__restore_attempt_delivery_ordering_index.sql
for run in 1 2 3 4 5; do
  docker exec -i relay-p05-task6-postgres psql -X -v ON_ERROR_STOP=1 -U p05 -d p05 < target/task-6-repeat-plans.sql
done > target/task-6-v14-steady-after.log 2>&1
```

| Query/schema shape | Five execution times (ms) | Median |
|---|---|---|
| List before V14 | 191.404, 191.688, 182.981, 187.192, 191.266 | 191.266 |
| List after V14 | 178.597, 152.925, 148.697, 152.380, 149.718 | 152.380 |
| Count before V14 | 88.085, 87.131, 87.996, 88.162, 88.098 | 88.085 |
| Count after V14 | 37.225, 37.687, 36.849, 36.306, 36.303 | 36.849 |

V14 restores the count's `Index Only Scan using idx_attempts_delivery_attempt_no -> Merge Join -> Unique`
without the extra Incremental Sort. All five post-V14 count plans use that access path. The list also returns to the
DESC index but retains its existing post-WindowAgg Incremental Sort in both shapes; no claim is made that every list
Sort disappears. Timing differences are representative host measurements, not production latency guarantees or tests.
The count median improves by 2.39x; the list median improves by 20.3%.

First complete before-V14 plans:

```text
Exact DeliveryStatusSpecifications app predicate/default sort and pageable count
                                                                                              QUERY PLAN
------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 Limit  (cost=19294.99..19295.04 rows=20 width=140) (actual time=191.212..191.223 rows=20 loops=1)
   Buffers: shared hit=100717
   ->  Sort  (cost=19294.99..19295.05 rows=25 width=140) (actual time=191.210..191.220 rows=20 loops=1)
         Sort Key: delivery_status.delivery_created_at DESC
         Sort Method: top-N heapsort  Memory: 34kB
         Buffers: shared hit=100717
         ->  Subquery Scan on delivery_status  (cost=656.37..19294.41 rows=25 width=140) (actual time=4.732..191.060 rows=1000 loops=1)
               Filter: (delivery_status.app_id = '00000000-0000-0000-0000-000000000003'::uuid)
               Rows Removed by Filter: 4000
               Buffers: shared hit=100714
               ->  Unique  (cost=656.37..19231.94 rows=4998 width=140) (actual time=4.728..190.657 rows=5000 loops=1)
                     Buffers: shared hit=100714
                     ->  Incremental Sort  (cost=656.37..18981.94 rows=100000 width=140) (actual time=4.728..186.002 rows=100000 loops=1)
                           Sort Key: a.delivery_id, a.attempt_no DESC
                           Presorted Key: a.delivery_id
                           Full-sort Groups: 2500  Sort Method: quicksort  Average Memory: 31kB  Peak Memory: 31kB
                           Buffers: shared hit=100714
                           ->  WindowAgg  (cost=652.97..15470.97 rows=100000 width=140) (actual time=4.546..118.095 rows=100000 loops=1)
                                 Buffers: shared hit=100711
                                 ->  Merge Join  (cost=652.97..13970.97 rows=100000 width=132) (actual time=4.452..74.866 rows=100000 loops=1)
                                       Merge Cond: (a.delivery_id = d.id)
                                       Buffers: shared hit=100711
                                       ->  Index Scan using uk_attempts_delivery_attempt_no on attempts a  (cost=0.42..11568.42 rows=100000 width=61) (actual time=0.009..46.706 rows=100000 loops=1)
                                             Buffers: shared hit=100590
                                       ->  Sort  (cost=652.55..665.05 rows=5000 width=87) (actual time=4.440..9.607 rows=99981 loops=1)
                                             Sort Key: d.id
                                             Sort Method: quicksort  Memory: 739kB
                                             Buffers: shared hit=121
                                             ->  Hash Join  (cost=171.73..345.36 rows=5000 width=87) (actual time=0.996..3.226 rows=5000 loops=1)
                                                   Hash Cond: (m.event_id = ev.id)
                                                   Buffers: shared hit=121
                                                   ->  Hash Join  (cost=170.61..320.00 rows=5000 width=97) (actual time=0.977..2.626 rows=5000 loops=1)
                                                         Hash Cond: (d.message_id = m.id)
                                                         Buffers: shared hit=120
                                                         ->  Hash Join  (cost=1.11..137.36 rows=5000 width=81) (actual time=0.020..0.994 rows=5000 loops=1)
                                                               Hash Cond: (d.endpoint_id = ep.id)
                                                               Buffers: shared hit=63
                                                               ->  Seq Scan on deliveries d  (cost=0.00..112.00 rows=5000 width=72) (actual time=0.005..0.270 rows=5000 loops=1)
                                                                     Buffers: shared hit=62
                                                               ->  Hash  (cost=1.05..1.05 rows=5 width=25) (actual time=0.005..0.005 rows=5 loops=1)
                                                                     Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                     Buffers: shared hit=1
                                                                     ->  Seq Scan on endpoints ep  (cost=0.00..1.05 rows=5 width=25) (actual time=0.002..0.002 rows=5 loops=1)
                                                                           Buffers: shared hit=1
                                                         ->  Hash  (cost=107.00..107.00 rows=5000 width=32) (actual time=0.932..0.933 rows=5000 loops=1)
                                                               Buckets: 8192  Batches: 1  Memory Usage: 377kB
                                                               Buffers: shared hit=57
                                                               ->  Seq Scan on messages m  (cost=0.00..107.00 rows=5000 width=32) (actual time=0.002..0.366 rows=5000 loops=1)
                                                                     Buffers: shared hit=57
                                                   ->  Hash  (cost=1.05..1.05 rows=5 width=22) (actual time=0.007..0.008 rows=5 loops=1)
                                                         Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                         Buffers: shared hit=1
                                                         ->  Seq Scan on events ev  (cost=0.00..1.05 rows=5 width=22) (actual time=0.003..0.003 rows=5 loops=1)
                                                               Buffers: shared hit=1
 Planning:
   Buffers: shared hit=501 dirtied=1
 Planning Time: 1.264 ms
 Execution Time: 191.404 ms
(58 rows)

                                                                                         QUERY PLAN
---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 Aggregate  (cost=10102.47..10102.48 rows=1 width=8) (actual time=87.970..87.981 rows=1 loops=1)
   Buffers: shared hit=713
   ->  Subquery Scan on delivery_status  (cost=654.53..10102.41 rows=25 width=0) (actual time=3.836..87.912 rows=1000 loops=1)
         Filter: (delivery_status.app_id = '00000000-0000-0000-0000-000000000003'::uuid)
         Rows Removed by Filter: 4000
         Buffers: shared hit=713
         ->  Unique  (cost=654.53..10039.94 rows=4998 width=1668) (actual time=3.832..87.576 rows=5000 loops=1)
               Buffers: shared hit=713
               ->  Incremental Sort  (cost=654.53..9789.94 rows=100000 width=1668) (actual time=3.831..82.743 rows=100000 loops=1)
                     Sort Key: a.delivery_id, a.attempt_no DESC
                     Presorted Key: a.delivery_id
                     Full-sort Groups: 2500  Sort Method: quicksort  Average Memory: 27kB  Peak Memory: 27kB
                     Buffers: shared hit=713
                     ->  Merge Join  (cost=652.97..6278.97 rows=100000 width=1668) (actual time=3.786..33.301 rows=100000 loops=1)
                           Merge Cond: (a.delivery_id = d.id)
                           Buffers: shared hit=713
                           ->  Index Only Scan using uk_attempts_delivery_attempt_no on attempts a  (cost=0.42..3876.42 rows=100000 width=20) (actual time=0.016..8.665 rows=100000 loops=1)
                                 Heap Fetches: 52
                                 Buffers: shared hit=592
                           ->  Sort  (cost=652.55..665.05 rows=5000 width=32) (actual time=3.763..7.708 rows=99981 loops=1)
                                 Sort Key: d.id
                                 Sort Method: quicksort  Memory: 466kB
                                 Buffers: shared hit=121
                                 ->  Hash Join  (cost=171.73..345.36 rows=5000 width=32) (actual time=0.849..2.887 rows=5000 loops=1)
                                       Hash Cond: (m.event_id = ev.id)
                                       Buffers: shared hit=121
                                       ->  Hash Join  (cost=170.61..320.00 rows=5000 width=48) (actual time=0.827..2.351 rows=5000 loops=1)
                                             Hash Cond: (d.message_id = m.id)
                                             Buffers: shared hit=120
                                             ->  Hash Join  (cost=1.11..137.36 rows=5000 width=48) (actual time=0.014..0.892 rows=5000 loops=1)
                                                   Hash Cond: (d.endpoint_id = ep.id)
                                                   Buffers: shared hit=63
                                                   ->  Seq Scan on deliveries d  (cost=0.00..112.00 rows=5000 width=64) (actual time=0.004..0.238 rows=5000 loops=1)
                                                         Buffers: shared hit=62
                                                   ->  Hash  (cost=1.05..1.05 rows=5 width=16) (actual time=0.004..0.005 rows=5 loops=1)
                                                         Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                         Buffers: shared hit=1
                                                         ->  Seq Scan on endpoints ep  (cost=0.00..1.05 rows=5 width=16) (actual time=0.002..0.003 rows=5 loops=1)
                                                               Buffers: shared hit=1
                                             ->  Hash  (cost=107.00..107.00 rows=5000 width=32) (actual time=0.808..0.809 rows=5000 loops=1)
                                                   Buckets: 8192  Batches: 1  Memory Usage: 377kB
                                                   Buffers: shared hit=57
                                                   ->  Seq Scan on messages m  (cost=0.00..107.00 rows=5000 width=32) (actual time=0.002..0.337 rows=5000 loops=1)
                                                         Buffers: shared hit=57
                                       ->  Hash  (cost=1.05..1.05 rows=5 width=16) (actual time=0.012..0.013 rows=5 loops=1)
                                             Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                             Buffers: shared hit=1
                                             ->  Seq Scan on events ev  (cost=0.00..1.05 rows=5 width=16) (actual time=0.008..0.009 rows=5 loops=1)
                                                   Buffers: shared hit=1
 Planning:
   Buffers: shared hit=40
 Planning Time: 0.495 ms
 Execution Time: 88.085 ms
(53 rows)
```

First complete after-V14 plans:

```text
Exact DeliveryStatusSpecifications app predicate/default sort and pageable count
                                                                                              QUERY PLAN
-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 Limit  (cost=18918.99..18919.04 rows=20 width=140) (actual time=178.429..178.438 rows=20 loops=1)
   Buffers: shared hit=100131 read=491
   ->  Sort  (cost=18918.99..18919.05 rows=25 width=140) (actual time=178.428..178.435 rows=20 loops=1)
         Sort Key: delivery_status.delivery_created_at DESC
         Sort Method: top-N heapsort  Memory: 34kB
         Buffers: shared hit=100131 read=491
         ->  Subquery Scan on delivery_status  (cost=656.29..18918.41 rows=25 width=140) (actual time=4.625..178.264 rows=1000 loops=1)
               Filter: (delivery_status.app_id = '00000000-0000-0000-0000-000000000003'::uuid)
               Rows Removed by Filter: 4000
               Buffers: shared hit=100128 read=491
               ->  Unique  (cost=656.29..18855.94 rows=4998 width=140) (actual time=4.622..177.845 rows=5000 loops=1)
                     Buffers: shared hit=100128 read=491
                     ->  Incremental Sort  (cost=656.29..18605.94 rows=100000 width=140) (actual time=4.621..172.676 rows=100000 loops=1)
                           Sort Key: a.delivery_id, a.attempt_no DESC
                           Presorted Key: a.delivery_id
                           Full-sort Groups: 2500  Sort Method: quicksort  Average Memory: 31kB  Peak Memory: 31kB
                           Buffers: shared hit=100128 read=491
                           ->  WindowAgg  (cost=652.97..15094.97 rows=100000 width=140) (actual time=4.452..137.719 rows=100000 loops=1)
                                 Buffers: shared hit=100125 read=491
                                 ->  Merge Join  (cost=652.97..13594.97 rows=100000 width=132) (actual time=4.354..89.367 rows=100000 loops=1)
                                       Merge Cond: (a.delivery_id = d.id)
                                       Buffers: shared hit=100125 read=491
                                       ->  Index Scan using idx_attempts_delivery_attempt_no on attempts a  (cost=0.42..11192.42 rows=100000 width=61) (actual time=0.009..58.951 rows=100000 loops=1)
                                             Buffers: shared hit=100004 read=491
                                       ->  Sort  (cost=652.55..665.05 rows=5000 width=87) (actual time=4.343..9.456 rows=99981 loops=1)
                                             Sort Key: d.id
                                             Sort Method: quicksort  Memory: 739kB
                                             Buffers: shared hit=121
                                             ->  Hash Join  (cost=171.73..345.36 rows=5000 width=87) (actual time=0.949..3.145 rows=5000 loops=1)
                                                   Hash Cond: (m.event_id = ev.id)
                                                   Buffers: shared hit=121
                                                   ->  Hash Join  (cost=170.61..320.00 rows=5000 width=97) (actual time=0.930..2.572 rows=5000 loops=1)
                                                         Hash Cond: (d.message_id = m.id)
                                                         Buffers: shared hit=120
                                                         ->  Hash Join  (cost=1.11..137.36 rows=5000 width=81) (actual time=0.023..1.006 rows=5000 loops=1)
                                                               Hash Cond: (d.endpoint_id = ep.id)
                                                               Buffers: shared hit=63
                                                               ->  Seq Scan on deliveries d  (cost=0.00..112.00 rows=5000 width=72) (actual time=0.006..0.266 rows=5000 loops=1)
                                                                     Buffers: shared hit=62
                                                               ->  Hash  (cost=1.05..1.05 rows=5 width=25) (actual time=0.004..0.006 rows=5 loops=1)
                                                                     Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                     Buffers: shared hit=1
                                                                     ->  Seq Scan on endpoints ep  (cost=0.00..1.05 rows=5 width=25) (actual time=0.002..0.002 rows=5 loops=1)
                                                                           Buffers: shared hit=1
                                                         ->  Hash  (cost=107.00..107.00 rows=5000 width=32) (actual time=0.877..0.878 rows=5000 loops=1)
                                                               Buckets: 8192  Batches: 1  Memory Usage: 377kB
                                                               Buffers: shared hit=57
                                                               ->  Seq Scan on messages m  (cost=0.00..107.00 rows=5000 width=32) (actual time=0.002..0.352 rows=5000 loops=1)
                                                                     Buffers: shared hit=57
                                                   ->  Hash  (cost=1.05..1.05 rows=5 width=22) (actual time=0.007..0.007 rows=5 loops=1)
                                                         Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                         Buffers: shared hit=1
                                                         ->  Seq Scan on events ev  (cost=0.00..1.05 rows=5 width=22) (actual time=0.002..0.003 rows=5 loops=1)
                                                               Buffers: shared hit=1
 Planning:
   Buffers: shared hit=509 read=6
 Planning Time: 1.249 ms
 Execution Time: 178.597 ms
(58 rows)

                                                                                       QUERY PLAN
----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 Aggregate  (cost=6215.51..6215.52 rows=1 width=8) (actual time=37.127..37.133 rows=1 loops=1)
   Buffers: shared hit=618
   ->  Subquery Scan on delivery_status  (cost=652.97..6215.45 rows=25 width=0) (actual time=3.786..37.070 rows=1000 loops=1)
         Filter: (delivery_status.app_id = '00000000-0000-0000-0000-000000000003'::uuid)
         Rows Removed by Filter: 4000
         Buffers: shared hit=618
         ->  Unique  (cost=652.97..6152.97 rows=4998 width=1668) (actual time=3.777..36.725 rows=5000 loops=1)
               Buffers: shared hit=618
               ->  Merge Join  (cost=652.97..5902.97 rows=100000 width=1668) (actual time=3.776..31.872 rows=100000 loops=1)
                     Merge Cond: (a.delivery_id = d.id)
                     Buffers: shared hit=618
                     ->  Index Only Scan using idx_attempts_delivery_attempt_no on attempts a  (cost=0.42..3500.42 rows=100000 width=20) (actual time=0.009..7.775 rows=100000 loops=1)
                           Heap Fetches: 52
                           Buffers: shared hit=497
                     ->  Sort  (cost=652.55..665.05 rows=5000 width=32) (actual time=3.763..7.495 rows=99981 loops=1)
                           Sort Key: d.id
                           Sort Method: quicksort  Memory: 466kB
                           Buffers: shared hit=121
                           ->  Hash Join  (cost=171.73..345.36 rows=5000 width=32) (actual time=0.830..2.944 rows=5000 loops=1)
                                 Hash Cond: (m.event_id = ev.id)
                                 Buffers: shared hit=121
                                 ->  Hash Join  (cost=170.61..320.00 rows=5000 width=48) (actual time=0.821..2.398 rows=5000 loops=1)
                                       Hash Cond: (d.message_id = m.id)
                                       Buffers: shared hit=120
                                       ->  Hash Join  (cost=1.11..137.36 rows=5000 width=48) (actual time=0.007..0.881 rows=5000 loops=1)
                                             Hash Cond: (d.endpoint_id = ep.id)
                                             Buffers: shared hit=63
                                             ->  Seq Scan on deliveries d  (cost=0.00..112.00 rows=5000 width=64) (actual time=0.002..0.251 rows=5000 loops=1)
                                                   Buffers: shared hit=62
                                             ->  Hash  (cost=1.05..1.05 rows=5 width=16) (actual time=0.002..0.003 rows=5 loops=1)
                                                   Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                   Buffers: shared hit=1
                                                   ->  Seq Scan on endpoints ep  (cost=0.00..1.05 rows=5 width=16) (actual time=0.001..0.002 rows=5 loops=1)
                                                         Buffers: shared hit=1
                                       ->  Hash  (cost=107.00..107.00 rows=5000 width=32) (actual time=0.810..0.811 rows=5000 loops=1)
                                             Buckets: 8192  Batches: 1  Memory Usage: 377kB
                                             Buffers: shared hit=57
                                             ->  Seq Scan on messages m  (cost=0.00..107.00 rows=5000 width=32) (actual time=0.002..0.337 rows=5000 loops=1)
                                                   Buffers: shared hit=57
                                 ->  Hash  (cost=1.05..1.05 rows=5 width=16) (actual time=0.005..0.005 rows=5 loops=1)
                                       Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                       Buffers: shared hit=1
                                       ->  Seq Scan on events ev  (cost=0.00..1.05 rows=5 width=16) (actual time=0.003..0.003 rows=5 loops=1)
                                             Buffers: shared hit=1
 Planning:
   Buffers: shared hit=40
 Planning Time: 0.452 ms
 Execution Time: 37.225 ms
(48 rows)
```

## Final regression and format evidence

Required test environment:

```bash
JWT_SECRET=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
RELAY_EMAIL_SENDER_EMAIL=test@example.com
RELAY_EMAIL_SENDER_NAME=Relay
BREVO_API_KEY=test-key
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

All host test commands prefix those values; Docker receives the same explicit test values in its local override.
Exact prescribed focused selection:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw \
  -Dtest=BackgroundExecutionContextPolicyTest,BackgroundExecutionCrossContextRegressionTest,ApacheResponseConsumptionIntegrationTest,WebhookDestinationAdversarialIntegrationTest,AttemptExecutionFencingIntegrationTest,DeliveryWorkerOwnershipFencingIntegrationTest,AttemptServiceMarkFailedAndCreateRetryAtomicityTest,AttemptSequenceMigrationPostgresTest,AttemptAllocationRepositoryPostgresTest,DeliveryReplayConcurrencyPostgresTest,DeliveryReplayLifecycleIntegrationTest,DeliveryReplayHttpIntegrationTest \
  -Drelay.retry.scheduling-enabled=false test
```

Exit 0, BUILD SUCCESS: 79 tests, 0 failures/errors/skips (1:28). Log: `target/task-6-final-focused.log`.
Counts: allocation repository 14; sequence migration 5; retry atomicity 1; P04 fencing 11; response consumption 5;
destination adversarial 11; worker ownership fencing 4; replay lifecycle 3; replay concurrency 13; replay HTTP 1;
background policy nested contexts 6; background cross-context 5.

Supplemental native/P04 and remaining P05 gates:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw \
  -Dtest=RepositoryPostgresAuditTest,AttemptExecutionMigrationLifecyclePostgresTest,ReconciliationSweeperIntegrationTest,AttemptAllocationMetricsTest,DeliveryStatusViewPostgresTest,MessageServiceTransactionIntegrationTest,AttemptServiceTest,DeliveryReplayServiceTest \
  -Drelay.retry.scheduling-enabled=false test
```

Exit 0, BUILD SUCCESS: 56 tests, 0 failures/errors/skips (36.183 s). Counts: native repository 7, P04 migration 1,
reconciliation 14, allocation metrics 5, view 1, Message transaction 2, Attempt service 21, replay service 5.
Both ordered parent/max operations and the latest finder execute on PostgreSQL in the native audit.
Log: `target/task-6-final-supplemental.log`.

The exact unmodified `make test-full-docker` initially failed before tests because the worktree has no .env.
A local ignored Compose override resets only the absent env_file and published app ports, supplies explicit test env,
uses host networking for Testcontainers, and excludes one confirmed hanging method:

```yaml
services:
  app:
    env_file: !reset []
    ports: !reset []
    network_mode: host
    environment:
      JWT_SECRET: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
      RELAY_EMAIL_SENDER_EMAIL: test@example.com
      RELAY_EMAIL_SENDER_NAME: Relay
      BREVO_API_KEY: test-key
      MAVEN_ARGS: '-Dtest=*,!BrevoEmailSenderTest#send_throwsEmailSendException_on429RateLimited'
```

Validation and practical full Docker target:

```bash
docker compose -f docker-compose.dev.yml -f target/task-6-compose-override.yml config --quiet
make COMPOSE='docker compose -f docker-compose.dev.yml -f target/task-6-compose-override.yml' test-full-docker
```

Both exit 0. Full Docker BUILD SUCCESS: 856 tests, 0 failures/errors/skips (5:57).
The excluded method is not included in JUnit's skipped count; all other tests are selected, including 10 passing
BrevoEmailSenderTest methods. Container Java: Temurin 21.0.12. The test-environment workaround changed no source,
Makefile, production Compose config, secrets, or Brevo test. Log: `target/task-6-final-full-docker.log`.

The Brevo exception was freshly reconfirmed before exclusion:

```bash
timeout 45s env JWT_SECRET=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef RELAY_EMAIL_SENDER_EMAIL=test@example.com RELAY_EMAIL_SENDER_NAME=Relay BREVO_API_KEY=test-key JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw '-Dtest=BrevoEmailSenderTest#send_throwsEmailSendException_on429RateLimited' test
```

Exit 124. Apache logs that the sole queued MockWebServer 429 response triggers automatic re-execution after one second;
the retry response is never queued, so the isolated method hangs. This is the known unrelated case, left unchanged.
Log: `target/task-6-brevo-recheck.log`.

Format/diff commands:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw spotless:check
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -DspotlessFiles=src/test/java/com/example/relay/attempt/infrastructure/AttemptSequenceMigrationPostgresTest.java,src/main/java/com/example/relay/attempt/application/AttemptAllocationMetrics.java spotless:apply
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -DspotlessFiles=src/test/java/com/example/relay/attempt/infrastructure/AttemptSequenceMigrationPostgresTest.java,src/main/java/com/example/relay/attempt/application/AttemptAllocationMetrics.java spotless:check
git diff --check
git diff --cached --check
```

Global check: exit 1, 179 existing Java violations, matching Task 1's baseline; no unrelated formatting changes.
Scoped apply/check: exit 0, BUILD SUCCESS; final diff checks: exit 0.
The full-format expectation is a documented baseline exception, not a claim that the global check passed.
No P00 autonomous component was globally enabled; explicitly opted-in lifecycle/background tests retain their config.

## Invariant mapping and self-review

| Required invariant | Fresh passing evidence |
|---|---|
| Exactly three creators; initial fixed 1 in Message transaction | complete source inventory; MessageServiceTransactionIntegrationTest (2), including rollback and active-subscription fan-out |
| Endpoint SHARE/active before Delivery UPDATE; locks retained through replay commit | allocation repository ordering/commit/deactivation cases and replay service concurrency cases |
| Retry Endpoint KEY SHARE -> Delivery UPDATE -> P04 positive/exact generation parent fence | repository ordering + retry service concurrency + P04 fencing classes |
| Current max+1 under the same Delivery lock, mandatory transaction, distinct histories concurrent | 14 allocation repository cases, 13 replay/retry concurrency cases, native repository audit |
| Current DEAD plus active Endpoint only; fast success rejects; fast DEAD gets next number | replay concurrency fast-terminal, simultaneous, deactivation, and retry-winner cases; HTTP/service tests |
| Replay/delete and retry/delete no 40P01; retry/deactivation compatible | named repository and service PostgreSQL cases pass |
| Rollback consumes no number, retry insert failure preserves parent IN_FLIGHT | allocation rollback and retry atomicity tests pass |
| New CREATED/SCHEDULED generation 0/null claim; only P04 grants execution authority | replay lifecycle/concurrency, P04 generation/ABA/stale completion, worker ownership fencing pass |
| Permanent positivity/uniqueness; historical-only contiguity | migration 5-case class, direct 23505/23514 checks, zero-row preflight and catalog |
| V12 -> V13 -> V14 preserves complete history and both final index roles | new serialized-history migration case; 100,000-row representative digest unchanged |
| View latest/count/history and bounded decision metrics | DeliveryStatusViewPostgresTest, replay lifecycle, AttemptAllocationMetricsTest pass |
| Native lock/max/finder syntax actually executed | RepositoryPostgresAuditTest 7 passing cases |
| P00–P04 and broad regressions | 79 focused + 56 supplemental + 856 practical Docker suite, with explicit single-method exclusion |

Self-review found no fourth writer, reverse parent-lock edge, weakened P04 predicate, silent conversion of V13 failures
to normal concurrency, new IN_FLIGHT/positive-generation creator, autonomous background enablement, or P06/P08/tier/
billing changes. The complete diff from a7148d7 for EndpointService, SubscriptionService, MessageService,
AttemptExecutionRepositoryImpl, DeliveryWorker, deliveryengine/config, and BrevoEmailSenderTest is empty.
The branch changed-path inventory remains P05-only; Task 1's prior AttemptRepositoryTest fixture correction is the
documented compatibility change. V13 is byte-for-byte unchanged. Metrics record Javadoc is documentation only and
clarifies pre-commit service decisions rather than durable totals; tags and runtime semantics are unchanged.

Authorized deviation: Task 6's original documentation-only scope now includes the owner-approved minimal V14 and its
migration preservation test. Task 1's historical single-Delivery evidence is retained and explicitly bounded; the
design/plan now record the discovered list/count regression, forward correction, final inventory, and DDL implications.
No other architectural change was needed.

## Rollout record and remaining operator actions

- Run/export exact read-only preflight on each actual target DB. Stop on duplicate/non-positive/non-contiguous histories;
  preserve evidence and obtain a case-specific data-owner decision. No automated deleting or renumbering.
- Apply V13 plus V14 schema-first within an approved DDL lock/time window. V14 is the authorized ordinary CREATE INDEX,
  so its build blocks conflicting writes; include that duration in the lock-time budget. Keep both final indexes.
- Deploy the ordered allocator to every instance. Mixed old/new binaries retain positivity/uniqueness but do not give
  full P05 current-eligibility/availability semantics until all allocators are updated.
- P04 semantic worker quiescing is unnecessary: V13/V14 do not reinterpret generation authority or invalidate in-flight
  capabilities. DDL lock budgeting is still required.
- Perform target post-deploy preflight and concurrent replay smoke; monitor allocation decisions/invariant failures,
  lock waits, deadlocks, and latency. Counters observe decisions before commit, not exact durable totals.
- Roll forward application failures; retain V13 permanent guarantees and V14 read access path. No automatic history
  repair, V13 rollback, P06/P08/tier/billing work, or external deployment was performed.
- Merge/deployment approval remains the owner's final step.

Task 6 files: V14 migration; AttemptSequenceMigrationPostgresTest; narrow AttemptAllocationMetrics Javadoc; approved
P05 design/plan; this verification record. The ignored full task report and local logs/SQL/Compose override live under
the task workspace/target. RTK.md was not found at referenced locations by the available filename searches; no Graphify
command was requested. Disposable PostgreSQL evidence is isolated from existing development or external databases.
