package com.example.relay.attempt.infrastructure;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Fails startup fast if the Postgres {@code attempts_status_check} constraint hasn't been widened
 * to accept the {@code SCHEDULED} attempt status yet.
 *
 * <p>{@code ddl-auto=update} never widens an existing CHECK constraint on its own, so a database
 * that had the {@code attempts} table before {@code SCHEDULED} was introduced boots cleanly with
 * no warning and only fails later, deep inside {@code AttemptService.createRetry}, after the
 * parent row has already committed as {@code FAILED_RETRYING} - silently losing that delivery.
 *
 * <p>Only runs under the {@code docker} profile (real Postgres). The default/test profile uses
 * H2, which rebuilds its schema from scratch on every run and never needs this migration.
 */
@Component
@Profile("docker")
public class ScheduledStatusConstraintGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduledStatusConstraintGuard.class);

    private static final String MIGRATION_PATH = "docs/migrations/2026-09-03-add-scheduled-status.sql";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void run(ApplicationArguments args) {
        List<?> results = entityManager
                .createNativeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'attempts_status_check'")
                .getResultList();

        if (results.isEmpty()) {
            // No such constraint at all - nothing restricting the status column, so SCHEDULED is
            // already accepted. Nothing to guard against.
            log.debug("No attempts_status_check constraint found; skipping SCHEDULED status check");
            return;
        }

        String definition = (String) results.get(0);
        if (definition == null || !definition.contains("SCHEDULED")) {
            throw new IllegalStateException(
                    "The attempts_status_check constraint on the attempts table does not allow the "
                            + "SCHEDULED status (current definition: " + definition + "). Run the migration at "
                            + MIGRATION_PATH + " against this database before starting the app.");
        }
    }
}
