package dev.sindic.enrollmenthub.geoscoring;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Base class for integration tests. Containers are started once for the entire JVM session
 * (singleton pattern) so that the Spring test context cache works correctly across test classes
 * that share the same context key. Ports are stable for the lifetime of the JVM; Testcontainers
 * Ryuk cleans up on JVM shutdown.
 */
@SpringBootTest
public abstract class BaseIntegrationTest {

    // Images pinned to immutable SHA256 digests for reproducible builds.
    // Update via Renovate (see renovate.json customManagers).
    private static final String RABBITMQ_IMAGE =
            "rabbitmq:4-management-alpine@sha256:b618738ea52cb6d0073ee9f0412ede133411ecacd7afa40f17be748a6d9a9ee1";
    private static final String VALKEY_IMAGE =
            "valkey/valkey:8-alpine@sha256:1cb6b20b70d927560cc4cc5397b5f045e74aa603ff7696274778880bb6fadc75";
    private static final String LIBPOSTAL_IMAGE =
            "pelias/libpostal-service:latest@sha256:af8a5c9b1e6eb366aa2c8c0a7530ea578029d5132cc8ceb9026a128653a9264a";
    private static final String NOMINATIM_IMAGE =
            "mediagis/nominatim:5.2@sha256:3c49ad9443baab1f1ea13a6b1355fa377ae5fb0874dc328cba9b97a0ca7914bb";

    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse(RABBITMQ_IMAGE).asCompatibleSubstituteFor("rabbitmq"))
                    .waitingFor(Wait.forLogMessage(".*Server startup complete.*\\n", 1));

    static final GenericContainer<?> VALKEY =
            new GenericContainer<>(DockerImageName.parse(VALKEY_IMAGE))
                    .withExposedPorts(6379);

    static final GenericContainer<?> LIBPOSTAL =
            new GenericContainer<>(DockerImageName.parse(LIBPOSTAL_IMAGE))
                    .withExposedPorts(4400)
                    .waitingFor(Wait.forHttp("/parse?address=test")
                            .forStatusCode(200)
                            .withStartupTimeout(Duration.ofSeconds(120)));

    static final GenericContainer<?> NOMINATIM =
            new GenericContainer<>(DockerImageName.parse(NOMINATIM_IMAGE))
                    .withExposedPorts(8080)
                    .withEnv("PBF_URL",
                            "https://download.geofabrik.de/europe/monaco-latest.osm.pbf")
                    .withEnv("REPLICATION_URL",
                            "https://download.geofabrik.de/europe/monaco-updates/")
                    .withCreateContainerCmdModifier(cmd ->
                            cmd.getHostConfig().withShmSize(1024L * 1024L * 1024L))
                    .waitingFor(Wait.forHttp("/status")
                            .forStatusCode(200)
                            .withStartupTimeout(Duration.ofMinutes(5)));

    static {
        RABBITMQ.start();
        VALKEY.start();
        LIBPOSTAL.start();
        NOMINATIM.start();
    }

    @DynamicPropertySource
    static void connectionProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
        registry.add("libpostal.host", LIBPOSTAL::getHost);
        registry.add("libpostal.port", () -> LIBPOSTAL.getMappedPort(4400));
        registry.add("nominatim.host", NOMINATIM::getHost);
        registry.add("nominatim.port", () -> NOMINATIM.getMappedPort(8080));
        registry.add("geocoding.provider", () -> "nominatim");
        registry.add("geocoding.cache.hmac-secret", () -> "integration-test-secret");
    }
}
