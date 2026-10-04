# P04 Task 7: final verification and writer audit

Date: 2026-10-04

## Result

The P04 writer audit is complete with zero unclassified production Attempt writers. The final inventory in `docs/reviews/probes/README.md` has 17 classified entries covering the three JPA insert paths, the status setter, execution SQL, ready-work SQL, field-only dead-letter SQL, read guards, and every Attempt migration DML statement.

The audit found a second, unused runtime-capable `AttemptRepository.claim(UUID, Instant)` SQL writer. It was not called from production, but left a second entry into `IN_FLIGHT` available and failed the obsolete-API audit. Following parent direction, I removed that repository method and its direct API tests. Setup in other repository tests now claims through `AttemptExecutionRepository`. The `AttemptRepositoryApiTest` regression assertion was run first and failed because the old method existed, then passed after removal. Scheduled-row rejection and `updated_at` advancement coverage remain on the active `AttemptExecutionRepository` path.

After cleanup, the only runtime entry into `IN_FLIGHT` is `AttemptService.claim → AttemptExecutionRepositoryImpl.claim`: its single update requires the target ID and `CREATED`, increments execution generation, and sets `execution_claimed_at` and `updated_at` atomically. Both authoritative completion statements require `id + IN_FLIGHT + matching generation + generation > 0` and clear claim time. `resetStuck` is the only `IN_FLIGHT → CREATED` SQL; it rechecks observed generation and grace age in the update and clears claim time. Ready publication status mutation selects only `SCHEDULED`; its lease/marker SQL and both notification SQL mutations do not change execution status/generation/claim time. PostgreSQL rejects both invalid status/claim timestamp shapes.

## Verification

Base implementation revision audited: `5e802797f2d07f003b8bfea887aaff326473da7e`.

Focused migration and writer regression command:

```bash
JWT_SECRET=0123456789abcdef0123456789abcdef RELAY_EMAIL_SENDER_EMAIL=test@example.com RELAY_EMAIL_SENDER_NAME=RelayTest BREVO_API_KEY=test-api-key ./mvnw test -Dtest=AttemptRepositoryApiTest,AttemptExecutionRepositoryPostgresTest,AttemptExecutionMigrationLifecyclePostgresTest,RepositoryPostgresAuditTest,AttemptRepositoryTest,ReadyWorkRepositoryPostgresTest
```

Result: **44 tests, 0 failures, 0 errors, 0 skipped**. All 12 migrations applied from an empty PostgreSQL 16.15 database; the V12 lifecycle test proved backfill and both consistency-constraint rejection shapes.

Full suite command:

```bash
JWT_SECRET=0123456789abcdef0123456789abcdef RELAY_EMAIL_SENDER_EMAIL=test@example.com RELAY_EMAIL_SENDER_NAME=RelayTest BREVO_API_KEY=test-api-key ./mvnw test
```

Result: **806 tests, 0 failures, 0 errors, 0 skipped**; `BUILD SUCCESS`, elapsed 9:12.

The corrected obsolete-API grep in the README produced no matches. Its `Attempt[),]` type boundary avoids matching `AttemptExecution` while still detecting bare `Attempt` parameters. The `UPDATE attempts` grep found nine statements: four in `AttemptExecutionRepositoryImpl`, three in `ReadyWorkRepositoryImpl`, and two in `AttemptRepository`; each is classified in the README matrix. Across migrations, V5 delivery backfill and V12 claim-time backfill are the only Attempt data mutations; other Attempt migration matches are schema/index/constraint/view definitions.

## Rollout and concerns

Mixed-version rolling deployment is unsafe. The exact required order is: quiesce old background writers; apply V12; deploy only binaries with fenced claim/completion/reconciliation; resume writers and workers after every active binary is fenced; observe ownership counters, in-flight age, and delivery health. The migration and runtime constraint require this sequencing.

No test assertions or retry/grace settings were weakened or changed. The code-removal deviation and remediation are recorded above and in the appended README evidence. Commit message for the planned boundary: `docs: record P04 fencing verification and rollout`; see the enclosing repository commit for its final revision.
