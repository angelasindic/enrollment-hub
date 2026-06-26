package dev.sindic.enrollmenthub.authorizationserver;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for authorization-server integration tests.
 * <p>
 * A single Postgres container is started once for the JVM session so the Spring test context cache
 * works across test classes sharing the same context key. Flyway runs the {@code authorization_server}
 * migrations against it on context start, so the SAS Jdbc services + JdbcUserDetailsManager exercise
 * the real schema. The image is pinned to an immutable digest (updated via Renovate).
 */
@SpringBootTest
public abstract class BaseIntegrationTest {

    // Pinned to an immutable SHA256 digest for reproducible builds (Renovate customManager).
    private static final String POSTGRES_IMAGE =
            "postgres:17-alpine@sha256:dc17045ccfd343b49600570ea734b9c4991cf1c3f3302e67df51e3b402dd55c4";

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
                    // Pin the search_path to this service's schema, matching the production datasource
                    // URL, so the unqualified SQL from the SAS Jdbc services + JdbcUserDetailsManager resolves.
                    .withUrlParam("currentSchema", "authorization_server");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void connectionProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
