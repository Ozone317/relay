# Task 3 report: candidate-owned recovery orchestration

## RED evidence

Moved the characterization to `PasswordResetRecoveryOwnershipPostgresTest`, renamed the three cases to express the safety properties, and inverted their assertions before changing orchestration. With a test-only `JWT_SECRET`, the required pre-fix command produced 3 tests, 3 assertion failures, 0 errors:

- `consumptionOfSelectedCandidateWinsBeforeRecoveryLock`: expected one row after consumption, observed two because old recovery created a successor.
- `dispatchConfirmationOfSelectedCandidateWinsBeforeRecoveryLock`: expected the confirmed T0 to remain unused, observed `used_at` set by stale recovery.
- `staleSelectedCandidateCannotInvalidateNewerDispatchConfirmedUserRequest`: expected T0/T1 only, observed a third row from stale recovery.

The first attempt without `JWT_SECRET` stopped at Spring context setup; the run above supplied the required test-only secret and demonstrated the expected behavioral failures against old orchestration.

## GREEN evidence

- Focused command `JWT_SECRET=test-only-jwt-secret-for-recovery-tests-32bytes ./mvnw -Dtest=PasswordResetTokenServiceTest,PasswordResetServiceTest,PasswordResetEmailRecoverySweeperTest,PasswordResetRecoveryOwnershipPostgresTest test`: 29 tests, 0 failures, 0 errors.
- `./mvnw -Dtest=PasswordResetEmailRecoverySweeperIntegrationTest test`: 3 tests, 0 failures, 0 errors. The real RabbitMQ/PostgreSQL path issued and dispatched exactly one successor for unchanged T0, retired an exhausted T0 without a successor, and left expired rows untouched.
- `./mvnw -Dtest=PasswordResetRecoveryRollbackPostgresTest test`: 2 tests, 0 failures, 0 errors. The added outer-service test observes `giveUpOn(candidateId)` followed by the attempted distinct successor save, propagates the injected exception, then confirms T0’s `used_at`, dispatch marker, and first-request timestamp are unchanged, only T0 remains, and `EmailDispatchPublisher.publish` was never called. The existing direct transactional rollback test also passes.
- `./mvnw -Dtest=PasswordResetMaintenanceConcurrencyPostgresTest test`: 3 tests, 0 failures, 0 errors. Concurrent sweeps now result in one candidate-owned successor/publication and one no-op.
- `./mvnw -DskipTests test-compile`: passed.

## Conversion and review

The unsafe characterization was moved out of the `characterization` package and no longer asserts the defect. All three converted PostgreSQL races pass. The confirmed-candidate case remains unused and confirmed with no successor or recovery publication; the consumed-candidate case remains consumed with no successor or recovery publication; the newer user request remains the sole usable confirmed token and sole publication.

Adversarial review found no stale entity or timestamp authorizing mutation: the sweeper forwards only candidate ID, observed owner ID, grace, and maximum window. `PasswordResetService` publishes only when the transactional result is `REISSUED`, using the returned successor token, user, and raw value. `LOST_RACE` cannot reach the publisher and logs at debug level. The token service remains a separate Spring bean, so the service publishes after its transaction proxy returns. Legitimate unchanged-candidate recovery still publishes once in integration coverage.

The repository-wide scan `rg -n 'reissueForRecovery|giveUpOnRecovery|issueAndDispatchForRecovery\\s*\\(\\s*User' src/main src/test` returned no matches. The old characterization path is gone and the replacement file is present at `src/test/java/com/example/relay/user/recovery/PasswordResetRecoveryOwnershipPostgresTest.java`.

`git diff --check` passed. `./mvnw spotless:check` remains red because the repository reports 196 Java files needing formatting and a cached index entry for the removed characterization path; formatting the whole repository would alter unrelated files. This is the only outstanding concern.
