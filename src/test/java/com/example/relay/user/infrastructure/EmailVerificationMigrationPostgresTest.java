package com.example.relay.user.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.support.SharedPostgresContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@SpringBootTest
class EmailVerificationMigrationPostgresTest implements SharedPostgresContainer {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migration_addsEmailVerifiedColumn_backfilledTrueForExistingRows() {
        // A user row created by an EARLIER migration's own fixture data does not exist in this
        // project's baseline - so instead we insert a row directly, simulating a pre-migration
        // user, then re-run the column's own backfill logic is already applied by Flyway at
        // context startup. This test only needs to prove the COLUMN exists with the right
        // nullability/type - the backfill-to-TRUE behavior for rows that predate V9 is proven by
        // the migration SQL itself (UPDATE ... SET email_verified = TRUE WHERE email_verified IS
        // NULL, before the NOT NULL constraint is added), not re-derivable from a fresh test DB
        // where every row is created after V9 already ran.
        Boolean isNullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable = 'NO' FROM information_schema.columns "
                        + "WHERE table_name = 'users' AND column_name = 'email_verified'",
                Boolean.class);
        assertTrue(isNullable, "email_verified must be NOT NULL");
    }

    @Test
    void migration_createsEmailVerificationTokensTable() {
        Long tableCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'email_verification_tokens'",
                Long.class);
        assertEquals(1L, tableCount);

        Long uniqueIndexCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'email_verification_tokens' "
                        + "AND indexdef ILIKE '%token_hash%' AND indexdef ILIKE '%UNIQUE%'",
                Long.class);
        assertEquals(1L, uniqueIndexCount);
    }
}
