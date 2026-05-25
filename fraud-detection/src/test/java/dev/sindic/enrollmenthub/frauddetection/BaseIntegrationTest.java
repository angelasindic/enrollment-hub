package dev.sindic.enrollmenthub.frauddetection;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests. The RabbitMQ container is started once per JVM session and
 * shared across test classes; Testcontainers Ryuk cleans up on shutdown. Fraud detection has no
 * other infrastructure dependency.
 */
@SpringBootTest
public abstract class BaseIntegrationTest {

    private static final String RABBITMQ_IMAGE =
            "rabbitmq:4-management-alpine@sha256:b618738ea52cb6d0073ee9f0412ede133411ecacd7afa40f17be748a6d9a9ee1";

    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse(RABBITMQ_IMAGE).asCompatibleSubstituteFor("rabbitmq"))
                    .waitingFor(Wait.forLogMessage(".*Server startup complete.*\\n", 1));

    static {
        RABBITMQ.start();
    }

    @DynamicPropertySource
    static void connectionProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
    }
}
