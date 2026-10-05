package com.example.relay.attempt.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.example.relay.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class AttemptSequenceMigrationPostgresTest implements SharedPostgresContainer {
    private static final String APP_ID = "00000000-0000-0000-0000-000000000003";
    private static final String ENDPOINT_ID = "00000000-0000-0000-0000-000000000005";
    private static final String MESSAGE_ID = "00000000-0000-0000-0000-000000000006";
    private static final String DELIVERY_ID = "00000000-0000-0000-0000-000000000007";

    @Test
    void v13AcceptsContiguousHistoryAndEnforcesPositiveUniqueNumbers() throws Exception {
        withV12Schema((schema, statement) -> {
            statement.execute(attemptInsert(8, 1, "DEAD"));
            statement.execute(attemptInsert(9, 2, "CREATED"));

            migrateV13(schema);

            assertTrue(constraintExists(statement, "attempts_attempt_no_positive"));
            assertTrue(constraintExists(statement, "uk_attempts_delivery_attempt_no"));
            assertFalse(indexExists(statement, "idx_attempts_delivery_attempt_no"));
            assertConstraintRejects(statement, attemptInsert(10, 1, "DEAD"), "23505");
            assertConstraintRejects(statement, attemptInsert(11, 0, "DEAD"), "23514");
            captureLatestAttemptPlanWithoutOldIndex(statement);
        });
    }

    @Test
    void v14RestoresMixedOrderIndexWithoutMutatingAttemptHistory() throws Exception {
        withV12Schema((schema, statement) -> {
            statement.execute(attemptInsert(8, 1, "FAILED_RETRYING"));
            statement.execute(attemptInsert(9, 2, "DEAD"));
            statement.execute(attemptInsert(10, 3, "SCHEDULED"));
            statement.execute("UPDATE attempts SET response_code = 503, response_body = 'response retained', "
                    + "last_error = 'diagnostic retained', latency_ms = 17, execution_generation = 2 "
                    + "WHERE attempt_no < 3");
            statement.execute("UPDATE attempts SET next_retry_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes' "
                    + "WHERE attempt_no = 3");
            List<String> before = attemptHistory(statement);
            assertEquals(3, before.size());

            migrateV13(schema);
            assertEquals(before, attemptHistory(statement));
            assertFalse(indexExists(statement, "idx_attempts_delivery_attempt_no"));

            migrateLatest(schema);

            assertEquals(before, attemptHistory(statement));
            assertTrue(constraintExists(statement, "attempts_attempt_no_positive"));
            assertTrue(constraintExists(statement, "uk_attempts_delivery_attempt_no"));
            assertTrue(indexExists(statement, "uk_attempts_delivery_attempt_no"));
            assertTrue(indexExists(statement, "idx_attempts_delivery_attempt_no"));
            try (var rows = statement.executeQuery("SELECT indexdef FROM pg_indexes WHERE schemaname = "
                    + "current_schema() AND indexname = 'idx_attempts_delivery_attempt_no'")) {
                assertTrue(rows.next());
                assertTrue(rows.getString(1).endsWith("USING btree (delivery_id, attempt_no DESC)"));
            }
            assertConstraintRejects(statement, attemptInsert(11, 2, "DEAD"), "23505");
            assertConstraintRejects(statement, attemptInsert(12, 0, "DEAD"), "23514");
            assertEquals(before, attemptHistory(statement));
        });
    }

    @Test
    void v13RefusesDuplicateDeliveryAttemptNumber() throws Exception {
        withV12Schema((schema, statement) -> {
            statement.execute(attemptInsert(8, 1, "DEAD"));
            statement.execute(attemptInsert(9, 1, "DEAD"));
            assertMigrationFailsWith(schema, statement, "duplicate");
        });
    }

    @Test
    void v13RefusesNonContiguousHistory() throws Exception {
        withV12Schema((schema, statement) -> {
            statement.execute(attemptInsert(8, 1, "DEAD"));
            statement.execute(attemptInsert(9, 3, "DEAD"));
            assertMigrationFailsWith(schema, statement, "non-contiguous");
        });
    }

    @Test
    void v13RefusesNonPositiveAttemptNumber() throws Exception {
        withV12Schema((schema, statement) -> {
            statement.execute(attemptInsert(8, 0, "DEAD"));
            assertMigrationFailsWith(schema, statement, "non-positive");
        });
    }

    private static void withV12Schema(SchemaTest test) throws Exception {
        String schema = "p05_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        try {
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).target("12").locations("classpath:db/migration").load()
                    .migrate();
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                seedParentChain(statement);
                test.run(schema, statement);
            }
        } finally {
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void seedParentChain(Statement statement) throws SQLException {
        statement.execute("INSERT INTO users(id,email,password,email_verified) VALUES "
                + "('00000000-0000-0000-0000-000000000001','p05@example.com','hash',true)");
        statement.execute("INSERT INTO environments(id,user_id,name,description,created_at,updated_at) VALUES "
                + "('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001',"
                + "'env','desc',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        statement.execute("INSERT INTO apps(id,name,environment_id,created_at) VALUES "
                + "('" + APP_ID + "','app','00000000-0000-0000-0000-000000000002',CURRENT_TIMESTAMP)");
        statement.execute("INSERT INTO events(id,name,app_id,created_at) VALUES "
                + "('00000000-0000-0000-0000-000000000004','event','" + APP_ID + "',CURRENT_TIMESTAMP)");
        statement.execute("INSERT INTO endpoints(id,name,url,signing_secret,is_active,app_id,created_at,updated_at) "
                + "VALUES ('" + ENDPOINT_ID + "','ep','https://example.com','secret',true,'" + APP_ID
                + "',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        statement.execute("INSERT INTO messages(id,app_id,event_id,body,created_at) VALUES "
                + "('" + MESSAGE_ID + "','" + APP_ID + "','00000000-0000-0000-0000-000000000004',"
                + "'{}',CURRENT_TIMESTAMP)");
        statement.execute("INSERT INTO deliveries(id,app_id,message_id,endpoint_id,created_at) VALUES "
                + "('" + DELIVERY_ID + "','" + APP_ID + "','" + MESSAGE_ID + "','" + ENDPOINT_ID
                + "',CURRENT_TIMESTAMP)");
    }

    private static String attemptInsert(int idSuffix, int attemptNo, String status) {
        return "INSERT INTO attempts(id,app_id,message_id,endpoint_id,delivery_id,attempt_no,status,created_at,updated_at) "
                + "VALUES ('%s','%s','%s','%s','%s',%d,'%s',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"
                        .formatted("00000000-0000-0000-0000-%012d".formatted(idSuffix), APP_ID, MESSAGE_ID,
                                ENDPOINT_ID, DELIVERY_ID, attemptNo, status);
    }

    private static void migrateLatest(String schema) {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
    }

    private static void migrateV13(String schema) {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).target("13").locations("classpath:db/migration").load()
                .migrate();
    }

    private static List<String> attemptHistory(Statement statement) throws SQLException {
        List<String> history = new ArrayList<>();
        try (var rows = statement.executeQuery("SELECT row_to_json(a)::text FROM attempts a ORDER BY id")) {
            while (rows.next()) {
                history.add(rows.getString(1));
            }
        }
        return history;
    }

    private static void assertMigrationFailsWith(String schema, Statement statement, String expectedText)
            throws SQLException {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> migrateLatest(schema));
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        assertTrue(messages.toString().contains(expectedText), messages.toString());
        assertFalse(constraintExists(statement, "attempts_attempt_no_positive"));
        assertFalse(constraintExists(statement, "uk_attempts_delivery_attempt_no"));
        assertTrue(indexExists(statement, "idx_attempts_delivery_attempt_no"));
    }

    private static boolean constraintExists(Statement statement, String name) throws SQLException {
        try (var rows = statement.executeQuery("SELECT EXISTS (SELECT 1 FROM pg_constraint c "
                + "WHERE c.conrelid = 'attempts'::regclass AND c.conname = '" + name + "')")) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    private static void assertConstraintRejects(Statement statement, String sql, String sqlState) {
        SQLException failure = assertThrows(SQLException.class, () -> statement.execute(sql));
        assertEquals(sqlState, failure.getSQLState());
    }

    private static void captureLatestAttemptPlanWithoutOldIndex(Statement statement) throws SQLException {
        // Multiple real deliveries and 20 terminal attempts each make the planner's choice meaningful.
        statement.execute("INSERT INTO messages(id,app_id,event_id,body,created_at) "
                + "SELECT gen_random_uuid(), '" + APP_ID + "', '00000000-0000-0000-0000-000000000004', "
                + "'{}', CURRENT_TIMESTAMP FROM generate_series(1, 100)");
        statement.execute("INSERT INTO deliveries(id,app_id,message_id,endpoint_id,created_at) "
                + "SELECT gen_random_uuid(), '" + APP_ID + "', id, '" + ENDPOINT_ID + "', CURRENT_TIMESTAMP "
                + "FROM messages WHERE id <> '" + MESSAGE_ID + "'");
        statement.execute("INSERT INTO attempts(id,app_id,message_id,endpoint_id,delivery_id,attempt_no,status,"
                + "created_at,updated_at) SELECT gen_random_uuid(), d.app_id, d.message_id, d.endpoint_id, "
                + "d.id, n, 'DEAD', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM deliveries d "
                + "CROSS JOIN generate_series(1, 20) AS n WHERE d.id <> '" + DELIVERY_ID + "'");
        String targetDeliveryId;
        try (var rows = statement.executeQuery("SELECT id FROM deliveries WHERE id <> '" + DELIVERY_ID
                + "' ORDER BY id LIMIT 1")) {
            assertTrue(rows.next());
            targetDeliveryId = rows.getString(1);
        }
        assertFalse(indexExists(statement, "idx_attempts_delivery_attempt_no"));
        statement.execute("ANALYZE attempts");
        List<String> lines = new ArrayList<>();
        try (var rows = statement.executeQuery("EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) "
                + "SELECT * FROM attempts WHERE delivery_id = '" + targetDeliveryId
                + "' ORDER BY attempt_no DESC LIMIT 1")) {
            while (rows.next()) {
                lines.add(rows.getString(1));
            }
        }
        String plan = String.join("\n", lines);
        System.out.println("P05 PostgreSQL 16 latest-Attempt plan (2,002 attempts; 101 deliveries; old index dropped):\n"
                + plan);
        assertTrue(plan.contains("Index Scan Backward using uk_attempts_delivery_attempt_no"), plan);
        assertFalse(plan.contains("Sort"), plan);
    }

    private static boolean indexExists(Statement statement, String name) throws SQLException {
        try (var rows = statement.executeQuery("SELECT to_regclass('" + name + "') IS NOT NULL")) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    @FunctionalInterface
    private interface SchemaTest {
        void run(String schema, Statement statement) throws Exception;
    }
}
