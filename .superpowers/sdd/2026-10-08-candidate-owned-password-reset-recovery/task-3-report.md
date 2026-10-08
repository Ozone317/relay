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

The Task 3 review correctly identified new formatting defects. Independent, cache-free `git archive` extracts of base `9a3477d` and initial Task 3 head `762f96f`, each checked with `./mvnw -q -DskipTests spotless:check`, reported **194** and **196** violating Java files respectively. The two newly failing files were `PasswordResetService.java` and `PasswordResetServiceTest.java`; the initial Task 3 commit caused that +2. The earlier attribution to a cached index entry and wholly pre-existing formatting debt was incorrect.

## Review-fix evidence

- Corrected the stale `issueAndDispatch(User)` Javadoc: ordinary reset requests call it; the sweeper uses candidate-aware `issueAndDispatchForRecovery(...)`. Removed task-created unused imports and aligned the changed recovery and rollback code with the configured formatter. No unrelated files were mass-formatted.
- Ran `JWT_SECRET=test-only-jwt-secret-for-recovery-tests-32bytes ./mvnw -Dtest=PasswordResetTokenServiceTest,PasswordResetServiceTest,PasswordResetEmailRecoverySweeperTest,PasswordResetRecoveryOwnershipPostgresTest,PasswordResetEmailRecoverySweeperIntegrationTest,PasswordResetRecoveryRollbackPostgresTest,PasswordResetMaintenanceConcurrencyPostgresTest test`: **37 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**.
- Repeated the cache-free Spotless check on a fresh archive of `762f96f` with the review fixes applied: **192** violating Java files. A separate `spotless:apply` in disposable copies and source-to-formatted file comparison confirmed counts of **194** at base and **192** after the fix. After normalizing the characterization-to-ownership test rename, the violating-file set has no additions; only `PasswordResetEmailRecoverySweeper.java` and `PasswordResetRecoveryRollbackPostgresTest.java` left the failing set. Direct source-to-formatted comparisons also confirmed `PasswordResetService.java` and `PasswordResetServiceTest.java` are formatter-clean. The remaining 192 failures were already present at base; a repository-wide Spotless check still exits nonzero for that existing formatting debt.
- `git diff --check` passed after the review fixes.
