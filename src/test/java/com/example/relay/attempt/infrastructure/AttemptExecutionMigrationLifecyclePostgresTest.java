package com.example.relay.attempt.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.example.relay.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
}
