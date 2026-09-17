package com.example.relay.user.infrastructure;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.support.SharedPostgresContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@SpringBootTest
class UserVersionMigrationPostgresTest implements SharedPostgresContainer {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migration_addsVersionColumn_notNullWithZeroDefault() {
        Boolean isNotNull = jdbcTemplate.queryForObject(
                "SELECT is_nullable = 'NO' FROM information_schema.columns "
                        + "WHERE table_name = 'users' AND column_name = 'version'",
                Boolean.class);
        assertTrue(isNotNull, "version must be NOT NULL");

        String columnDefault = jdbcTemplate.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_name = 'users' AND column_name = 'version'",
                String.class);
        assertTrue(columnDefault != null && columnDefault.contains("0"),
                "version must default to 0 for rows inserted without specifying it");
    }
}
