package com.example.relay.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL container, started once per test JVM run and shared by every test class that
 * implements this interface, regardless of whether the class is a {@code @DataJpaTest} slice or a
 * full {@code @SpringBootTest} — those are different Spring context configurations and would
 * otherwise each get their own container, even with Spring's own test-context caching (which only
 * reuses a container across tests sharing an identical context configuration).
 */
public interface SharedPostgresContainer {

    PostgreSQLContainer<?> POSTGRES = createAndStart();

    private static PostgreSQLContainer<?> createAndStart() {
        // Every distinct @SpringBootTest/@DataJpaTest configuration gets its own cached Spring
        // ApplicationContext (and therefore its own HikariCP pool) against this one shared
        // container. With ~30 test classes and several genuinely distinct configurations, enough
        // pools can be open at once to exceed Postgres's default max_connections (observed: "sorry,
        // too many clients already"). Raised well above what even a large number of concurrently
        // cached contexts could need; this only affects the test container, not application config.
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                .withCommand("postgres", "-c", "max_connections=300");
        container.start();
        return container;
    }

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }
}
