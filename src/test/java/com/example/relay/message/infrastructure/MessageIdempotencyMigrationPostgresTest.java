package com.example.relay.message.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.example.relay.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class MessageIdempotencyMigrationPostgresTest implements SharedPostgresContainer {
    private static final UUID USER_ID = id(1);
    private static final UUID ENVIRONMENT_ID = id(2);
    private static final UUID APP_ID = id(3);
    private static final UUID EVENT_ID = id(4);
    private static final UUID MESSAGE_ID = id(5);
    private static final UUID ACCEPTED_MESSAGE_ID = id(6);
    private static final UUID LEGACY_MESSAGE_ID = MESSAGE_ID;

    @Test
    void v15AddsDeferredMessageIdempotencyAuthorityWithoutChangingV14Rows() throws Exception {
        String schema = "p07_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        try {
            migrate(schema, "14");
            String before;
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                seedV14Graph(connection);
                assertNull(regclass(statement, "message_idempotency"), "V14 must not contain the V15 authority table");
                before = v14Snapshot(statement);
            }

            migrate(schema, null);

            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                assertNotNull(regclass(statement, "message_idempotency"),
                        "latest migration must add the V15 authority table");
                assertEquals(before, v14Snapshot(statement), "V15 must leave all V14 seed rows unchanged");
                assertCatalogConstraints(statement);
                assertPrimaryKeyColumns(statement);
                assertNoCompositeOwnershipOrResultForeignKey(statement);
                assertInsertIdentityBeforeMessage(connection);
                assertMissingMessageFailsAtCommit(connection);
                assertMissingTargetsFailIndependently(connection);
                assertDuplicateScopeKeyFails(connection);
                assertDuplicateMessageResultFails(connection);
                assertInvalidFingerprintVersionFails(connection);
                assertLegacyMessageNeedsNoIdentity(connection);
            }
        } finally {
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static void seedV14Graph(Connection connection) throws SQLException {
        insert(connection, "INSERT INTO users(id,email,password,email_verified) VALUES (?,?,?,true)", USER_ID,
                "p07@example.com", "hash");
        insert(connection, "INSERT INTO environments(id,user_id,name,description,created_at,updated_at) "
                + "VALUES (?,?,?,'desc',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", ENVIRONMENT_ID, USER_ID, "env");
        insert(connection, "INSERT INTO apps(id,name,environment_id,created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)",
                APP_ID, "app", ENVIRONMENT_ID);
        insert(connection, "INSERT INTO events(id,name,app_id,created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)", EVENT_ID,
                "event", APP_ID);
        insert(connection,
                "INSERT INTO messages(id,app_id,event_id,body,created_at) " + "VALUES (?,?,?,'{}',CURRENT_TIMESTAMP)",
                MESSAGE_ID, APP_ID, EVENT_ID);
    }

    private static void assertCatalogConstraints(Statement statement) throws SQLException {
        List<String> constraints = new ArrayList<>();
        try (var rows = statement.executeQuery("SELECT conname, contype, condeferrable, condeferred "
                + "FROM pg_constraint WHERE conrelid = 'message_idempotency'::regclass ORDER BY conname")) {
            while (rows.next()) {
                constraints.add(rows.getString("conname") + ":" + rows.getString("contype") + ":"
                        + rows.getBoolean("condeferrable") + ":" + rows.getBoolean("condeferred"));
            }
        }
        assertEquals(List.of("ck_message_idempotency_fingerprint_version:c:false:false",
                "ck_message_idempotency_key_length:c:false:false", "fk_message_idempotency_message:f:true:true",
                "message_idempotency_app_id_fkey:f:false:false", "message_idempotency_user_id_fkey:f:false:false",
                "pk_message_idempotency:p:false:false", "uk_message_idempotency_message:u:false:false"), constraints);
    }

    private static void assertPrimaryKeyColumns(Statement statement) throws SQLException {
        try (var rows = statement.executeQuery("SELECT array_agg(a.attname ORDER BY k.ordinality) "
                + "FROM pg_constraint c CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ordinality) "
                + "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum "
                + "WHERE c.conrelid = 'message_idempotency'::regclass AND c.contype = 'p'")) {
            assertTrue(rows.next());
            assertEquals("{user_id,app_id,idempotency_key}", rows.getString(1));
        }
    }

    private static void assertNoCompositeOwnershipOrResultForeignKey(Statement statement) throws SQLException {
        // Separate FKs prove that each referenced row exists. They do not prove that user_id owns app_id
        // or that the referenced Message belongs to app_id; V15 intentionally adds neither equality rule.
        try (var rows = statement.executeQuery("SELECT count(*) FROM pg_constraint c "
                + "WHERE c.conrelid = 'message_idempotency'::regclass AND c.contype = 'f' "
                + "AND cardinality(c.conkey) > 1")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    private static void assertInsertIdentityBeforeMessage(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try {
            insertIdentity(connection, USER_ID, APP_ID, "deferred", 1, ACCEPTED_MESSAGE_ID);
            insert(connection, "INSERT INTO messages(id,app_id,event_id,body,created_at) "
                    + "VALUES (?,?,?,'{}',CURRENT_TIMESTAMP)", ACCEPTED_MESSAGE_ID, APP_ID, EVENT_ID);
            connection.commit();
        } finally {
            connection.setAutoCommit(true);
        }
        assertEquals(1, scalar(connection, "SELECT count(*) FROM message_idempotency WHERE message_id = ?",
                ACCEPTED_MESSAGE_ID));
    }

    private static void assertMissingTargetsFailIndependently(Connection connection) throws SQLException {
        assertFails("23503", () -> inTransaction(connection,
                () -> insertIdentity(connection, id(101), APP_ID, "missing-user", 1, MESSAGE_ID)));
        assertFails("23503", () -> inTransaction(connection,
                () -> insertIdentity(connection, USER_ID, id(103), "missing-app", 1, MESSAGE_ID)));
    }

    private static void assertMissingMessageFailsAtCommit(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try {
            assertDoesNotThrow(() -> insertIdentity(connection, USER_ID, APP_ID, "missing-message", 1, id(108)));
            SQLException failure = assertThrows(SQLException.class, connection::commit);
            assertEquals("23503", failure.getSQLState());
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    private static void assertDuplicateScopeKeyFails(Connection connection) throws SQLException {
        assertFails("23505", () -> inTransaction(connection,
                () -> insertIdentity(connection, USER_ID, APP_ID, "deferred", 1, id(105))));
    }

    private static void assertDuplicateMessageResultFails(Connection connection) throws SQLException {
        assertFails("23505", () -> inTransaction(connection,
                () -> insertIdentity(connection, USER_ID, APP_ID, "another-key", 1, ACCEPTED_MESSAGE_ID)));
    }

    private static void assertInvalidFingerprintVersionFails(Connection connection) throws SQLException {
        assertFails("23514", () -> inTransaction(connection,
                () -> insertIdentity(connection, USER_ID, APP_ID, "bad-version", 2, id(106))));
    }

    private static void assertLegacyMessageNeedsNoIdentity(Connection connection) throws SQLException {
        assertEquals(0,
                scalar(connection, "SELECT count(*) FROM message_idempotency WHERE message_id = ?", LEGACY_MESSAGE_ID));
        insert(connection,
                "INSERT INTO messages(id,app_id,event_id,body,created_at) " + "VALUES (?,?,?,'{}',CURRENT_TIMESTAMP)",
                id(107), APP_ID, EVENT_ID);
        assertEquals(0, scalar(connection, "SELECT count(*) FROM message_idempotency WHERE message_id = ?", id(107)));
    }

    private static String v14Snapshot(Statement statement) throws SQLException {
        return tableSnapshot(statement, "users") + tableSnapshot(statement, "environments")
                + tableSnapshot(statement, "apps") + tableSnapshot(statement, "events")
                + tableSnapshot(statement, "messages");
    }

    private static String tableSnapshot(Statement statement, String table) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (var result = statement.executeQuery("SELECT row_to_json(t)::text FROM " + table + " t ORDER BY id")) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return table + rows;
    }

    private static void insertIdentity(Connection connection, UUID userId, UUID appId, String key,
            int fingerprintVersion, UUID messageId) throws SQLException {
        insert(connection,
                "INSERT INTO message_idempotency(user_id,app_id,idempotency_key,fingerprint_version,"
                        + "message_id,accepted_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)",
                userId, appId, key, fingerprintVersion, messageId);
    }

    private static void insert(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private static int scalar(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private static String regclass(Statement statement, String relation) throws SQLException {
        try (var rows = statement.executeQuery("SELECT to_regclass('" + relation + "')")) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static void inTransaction(Connection connection, SqlAction action) throws SQLException {
        connection.setAutoCommit(false);
        try {
            action.run();
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void assertFails(String sqlState, SqlAction action) {
        SQLException failure = assertThrows(SQLException.class, () -> action.run());
        assertEquals(sqlState, failure.getSQLState());
    }

    private static void migrate(String schema, String target) {
        var configuration =
                Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                        .schemas(schema).defaultSchema(schema).locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
