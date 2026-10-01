package com.example.relay.attempt.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.example.relay.attempt.application.AttemptExecution;
import com.example.relay.attempt.application.AttemptMutationOutcome;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("integration")
class AttemptExecutionMigrationLifecyclePostgresTest implements SharedPostgresContainer {
    @Test
    void v12BackfillsLegacyInFlightAsGenerationZeroAndRejectsInconsistentShapes() throws Exception {
        String schema = "p04_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        try {
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).target("11").locations("classpath:db/migration").load()
                    .migrate();
            try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                    POSTGRES.getPassword()); Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.execute("INSERT INTO users(id,email,password,email_verified) VALUES ('00000000-0000-0000-0000-000000000001','legacy@example.com','hash',true)");
                statement.execute("INSERT INTO environments(id,user_id,name,description,created_at,updated_at) VALUES ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','env','desc',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
                statement.execute("INSERT INTO apps(id,name,environment_id,created_at) VALUES ('00000000-0000-0000-0000-000000000003','app','00000000-0000-0000-0000-000000000002',CURRENT_TIMESTAMP)");
                statement.execute("INSERT INTO events(id,name,app_id,created_at) VALUES ('00000000-0000-0000-0000-000000000004','event','00000000-0000-0000-0000-000000000003',CURRENT_TIMESTAMP)");
                statement.execute("INSERT INTO endpoints(id,name,url,signing_secret,is_active,app_id,created_at,updated_at) VALUES ('00000000-0000-0000-0000-000000000005','ep','https://example.com','secret',true,'00000000-0000-0000-0000-000000000003',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
                statement.execute("INSERT INTO messages(id,app_id,event_id,body,created_at) VALUES ('00000000-0000-0000-0000-000000000006','00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000004','{}',CURRENT_TIMESTAMP)");
                statement.execute("INSERT INTO deliveries(id,app_id,message_id,endpoint_id,created_at) VALUES ('00000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000006','00000000-0000-0000-0000-000000000005',CURRENT_TIMESTAMP)");
                statement.execute("INSERT INTO attempts(id,app_id,message_id,endpoint_id,delivery_id,attempt_no,status,created_at,updated_at) VALUES ('00000000-0000-0000-0000-000000000008','00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000006','00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000007',1,'IN_FLIGHT',CURRENT_TIMESTAMP,'2000-01-01T00:00:00Z')");
            }
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
            try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                    POSTGRES.getPassword()); Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                try (var rows = statement.executeQuery("SELECT status, execution_generation, execution_claimed_at, updated_at FROM attempts")) {
                    assertTrue(rows.next());
                    assertEquals("IN_FLIGHT", rows.getString("status"));
                    assertEquals(0L, rows.getLong("execution_generation"));
                    assertEquals(rows.getTimestamp("updated_at"), rows.getTimestamp("execution_claimed_at"));
                }
                assertConstraintRejects(statement, "UPDATE attempts SET execution_generation = -1");
                assertConstraintRejects(statement, "UPDATE attempts SET execution_claimed_at = NULL");
                assertConstraintRejects(statement, "UPDATE attempts SET status = 'CREATED' WHERE execution_claimed_at IS NOT NULL");
            }
            assertMigratedExecutionLifecycle(schema);
        } finally {
            try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                    POSTGRES.getPassword()); Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static void assertConstraintRejects(Statement statement, String sql) throws SQLException {
        SQLException exception = assertThrows(SQLException.class, () -> statement.executeUpdate(sql));
        assertEquals("23514", exception.getSQLState());
    }

    private void assertMigratedExecutionLifecycle(String schema) {
        String jdbcUrl = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema;
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(POSTGRES.getDriverClassName());
        dataSource.setUrl(jdbcUrl);
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        AttemptExecutionRepository repository = new AttemptExecutionRepositoryImpl(namedJdbc);

        UUID attemptId = UUID.fromString("00000000-0000-0000-0000-000000000008");
        Instant legacyClaimedAt = jdbc.queryForObject(
                "SELECT execution_claimed_at FROM attempts WHERE id = ?", Instant.class, attemptId);
        assertEquals(0L, jdbc.queryForObject(
                "SELECT execution_generation FROM attempts WHERE id = ?", Long.class, attemptId));
        assertEquals(AttemptStatus.IN_FLIGHT.name(), jdbc.queryForObject(
                "SELECT status FROM attempts WHERE id = ?", String.class, attemptId));

        Attempt attempt = mock(Attempt.class);
        when(attempt.getId()).thenReturn(attemptId);
        AttemptExecution generationZero = new AttemptExecution(attempt, 0L, legacyClaimedAt);
        assertEquals(AttemptMutationOutcome.OWNERSHIP_LOST,
                repository.markSucceeded(generationZero, 200, "stale", 9L));
        int childrenBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempts WHERE delivery_id = '00000000-0000-0000-0000-000000000007'",
                Integer.class);
        assertEquals(AttemptMutationOutcome.OWNERSHIP_LOST,
                repository.markFailed(generationZero, AttemptStatus.FAILED_RETRYING, Instant.now(),
                        500, "stale", "stale", 9L));
        int childrenAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempts WHERE delivery_id = '00000000-0000-0000-0000-000000000007'",
                Integer.class);
        assertEquals(childrenBefore, childrenAfter,
                "the zero-row parent transition must not create a retry child");
        assertEquals(AttemptStatus.IN_FLIGHT.name(), jdbc.queryForObject(
                "SELECT status FROM attempts WHERE id = ?", String.class, attemptId));

        assertTrue(repository.findStaleInFlight(Duration.ofSeconds(1), 10).stream()
                .anyMatch(candidate -> candidate.id().equals(attemptId) && candidate.generation() == 0L));
        assertEquals(1, repository.resetStuck(attemptId, 0L, Duration.ofSeconds(1)));
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM attempts WHERE id = ?", String.class, attemptId));
        assertNull(jdbc.queryForObject(
                "SELECT execution_claimed_at FROM attempts WHERE id = ?", Instant.class, attemptId));

        var replacement = repository.claim(attemptId).orElseThrow();
        assertEquals(1L, replacement.generation());
        assertTrue(replacement.claimedAt().isAfter(legacyClaimedAt));
        assertEquals(AttemptMutationOutcome.APPLIED, repository.markFailed(
                new AttemptExecution(attempt, replacement.generation(), replacement.claimedAt()),
                AttemptStatus.DEAD, Instant.now().plusSeconds(600), 410, "gone", "terminal", 11L));
        var terminal = jdbc.queryForMap("SELECT status, next_retry_at, response_code, response_body, last_error, "
                + "latency_ms, execution_claimed_at FROM attempts WHERE id = ?", attemptId);
        assertEquals("DEAD", terminal.get("status"));
        assertNull(terminal.get("next_retry_at"));
        assertEquals(410, terminal.get("response_code"));
        assertEquals("gone", terminal.get("response_body"));
        assertEquals("terminal", terminal.get("last_error"));
        assertEquals(11L, ((Number) terminal.get("latency_ms")).longValue());
        assertNull(terminal.get("execution_claimed_at"));
    }
}
