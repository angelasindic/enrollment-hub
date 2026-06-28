package dev.sindic.enrollmenthub.decisionengine;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for decision-engine integration tests.
 * <p>
 * Containers are started once for the entire JVM session (singleton pattern) so that
 * the Spring test context cache works correctly across test classes that share the same
 * context key. Ports are stable for the lifetime of the JVM; Testcontainers Ryuk cleans
 * up on JVM shutdown.
 * <p>
 * Images are pinned to immutable SHA256 digests for reproducible builds.
 * Update via Renovate (see {@code renovate.json} customManagers).
 */
@SpringBootTest
public abstract class BaseIntegrationTest {

    // Images pinned to immutable SHA256 digests for reproducible builds.
    // Update via Renovate (see renovate.json customManagers).
    private static final String POSTGRES_IMAGE =
            "postgres:16@sha256:80dee66a0ba95a54d143008143e5d7ef628c0e8d5e0666b39d13c8bac3377953";
    private static final String RABBITMQ_IMAGE =
            "rabbitmq:4-management-alpine@sha256:b618738ea52cb6d0073ee9f0412ede133411ecacd7afa40f17be748a6d9a9ee1";

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));

    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse(RABBITMQ_IMAGE).asCompatibleSubstituteFor("rabbitmq"))
                    .waitingFor(Wait.forLogMessage(".*Server startup complete.*\\n", 1));

    static {
        POSTGRES.start();
        RABBITMQ.start();
    }

    @DynamicPropertySource
    static void connectionProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
    }
}
