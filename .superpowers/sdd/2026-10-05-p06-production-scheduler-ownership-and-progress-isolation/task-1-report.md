# Task 1 report: explicit recurring topology and fail-fast routing

## Result

Implemented explicit scheduler ownership for the five recurring callbacks. Delivery retry and dispatch share the two-worker delivery-progress pool; reconciliation owns a one-worker pool; password-reset recovery and cleanup share the two-worker maintenance pool. The webhook deadline pool is created for its separately owned domain. The default `taskScheduler` is now a threadless guard that rejects every scheduling method with the stable classification marker.

Moved `applicationClock` and `readyWorkConfirmationExecutor` unchanged into `DeliveryEngineAsyncConfig`. The retry/dispatcher smoke fixture and P00 cross-context fixture now register the approved scheduler names. `ReconciliationSweeper.scheduledSweep()` and its synchronous two-phase work were left intact.

## RED evidence

- Before implementation, `./mvnw -q test -Dtest=ProductionSchedulerTopologyTest` failed on the expected missing route: `ReconciliationSweeper.scheduledSweep()` had an empty scheduler value instead of `deliveryReconciliationTaskScheduler`.
- Before implementation, `./mvnw -q test -Dtest=ProductionSchedulerTopologyTest,ScheduledProgressIsolationTest` reported that route mismatch plus missing `ProductionSchedulerConfig` for all six pool/isolation cases.
- The requested RED selection also exposed absent `JWT_SECRET` in full Spring contexts. Added the four inert values from the brief as test-only dynamic properties in `BackgroundExecutionContextPolicyTest`; no application configuration was added for local setup.

## GREEN evidence

The focused GREEN selection passed, including `PasswordResetTokenCleanupTaskTest`:

```bash
./mvnw -q -DJWT_SECRET=test-only-jwt-secret-not-for-signing \
  -DRELAY_EMAIL_SENDER_EMAIL=test@example.invalid \
  -DRELAY_EMAIL_SENDER_NAME='Test Only' -DBREVO_API_KEY=test-only-api-key \
  test -Dtest=ProductionSchedulerTopologyTest,ScheduledProgressIsolationTest,ScheduledLoopEnabledSmokeTest,RetrySchedulerPostgresTest,ReadyWorkDispatcherIntegrationTest,ReconciliationSweeperIntegrationTest,PasswordResetEmailRecoverySweeperIntegrationTest,PasswordResetTokenCleanupTaskTest
```

Also passed:

- `./mvnw -q test -Dtest=ProductionSchedulerTopologyTest,ScheduledProgressIsolationTest,ScheduledLoopEnabledSmokeTest`
- `./mvnw -q test -Dtest=BackgroundExecutionContextPolicyTest` (its full-context fixtures provide inert test-only values)
- `./mvnw -q -DJWT_SECRET=test-only-jwt-secret-not-for-signing -DRELAY_EMAIL_SENDER_EMAIL=test@example.invalid -DRELAY_EMAIL_SENDER_NAME='Test Only' -DBREVO_API_KEY=test-only-api-key test -Dtest=BackgroundExecutionCrossContextRegressionTest`
- `git diff --check`

The GREEN suite ran with Spring 6.2.19. It registered unqualified, approved, and unknown synthetic callbacks through `@EnableScheduling`; the approved callback registered once on its named scheduler, while an unknown name failed refresh through Spring's qualifier-resolution path.

## Adversarial review and self-review

- Removing any one of the five production qualifiers changes the exact route inventory assertion; adding another `@Scheduled` production method changes the P00 scheduled inventory assertion.
- Blocking password maintenance did not stop delivery progress. Blocking both delivery workers did not stop reconciliation or password maintenance. The tests also prove one free pool member runs work, and that a third task queues while both delivery or both password-maintenance members are occupied.
- A throwing reconciliation callback was logged and did not prevent a following callback from using that one-worker pool. Closing the application context while both delivery workers were blocked completed after their release.
- The guard owns no executor or thread. Production callbacks name their approved schedulers; no leadership, lock, bean ordering, or programmatic recurring registration was introduced.
- The first full-context integration attempt without environment values failed on the missing placeholder, as noted above. Rerunning with the brief's inert values passed. The adversarial callback test intentionally emits one scheduler error log.

## Commit

`fb2d5ce feat: isolate production scheduler ownership`
