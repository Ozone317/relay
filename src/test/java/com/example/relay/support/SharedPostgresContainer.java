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
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));
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
