package dev.sindic.enrollmenthub.geoscoring.amqp;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.contracts.events.GeoScoreRequest;
import dev.sindic.enrollmenthub.contracts.events.GeoScoreResult;
import dev.sindic.enrollmenthub.geoscoring.BaseIntegrationTest;
import dev.sindic.enrollmenthub.geoscoring.service.GeocodingService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * Drives the geo-scoring listener over a real broker. The request queue and its DLX/DLQ are owned
 * by the decision-engine in production (ADR-13 §Channel Ownership); since the decision-engine is
 * not present in this isolated context, {@link RequestTopology} declares that topology plus a
 * capture queue bound to the result exchange.
 */
class GeoScoreRequestListenerIT extends BaseIntegrationTest {

    private static final String REQUEST_EXCHANGE     = "enrollment.check.request";
    private static final String GEO_KEY              = "geo.score";
    private static final String REQUEST_QUEUE        = "geo.scoring.requests.queue";
    private static final String REQUEST_DLX          = "geo.scoring.requests.dlx";
    private static final String REQUEST_DLQ          = "geo.scoring.requests.queue.dlq";
    private static final String REQUEST_DLQ_RK       = "geo.scoring.requests.dead-letter";
    private static final String RESULT_EXCHANGE      = "enrollment.check.result";
    private static final String RESULT_CAPTURE_QUEUE = "test.geo.result.capture";

    @Autowired RabbitTemplate rabbitTemplate;
    @Value("${spring.rabbitmq.listener.simple.prefetch}") int prefetch;

    @MockitoSpyBean GeoScoreRequestListener listener;
    @MockitoSpyBean GeocodingService geocodingService;

    @Test
    void listenerConsumes_withThePrefetchFromTheConfiguration() throws Exception {
        // Asked of the broker, which enforces the limit: every consumer on the queue has to carry
        // the configured prefetch. A factory that skips Boot's listener properties consumes with
        // the Spring AMQP default of 250.
        var consumers = RABBITMQ.execInContainer(
                "rabbitmqctl", "--quiet", "list_consumers", "queue_name", "prefetch_count").getStdout();

        assertThat(consumers.lines().filter(line -> line.startsWith(REQUEST_QUEUE + "\t")))
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).endsWith("\t" + prefetch));
    }

    @Test
    void handleGeoScoreRequest_publishesGeoScoreResult() {
        var enrollmentId = UUID.randomUUID();
        rabbitTemplate.convertAndSend(REQUEST_EXCHANGE, GEO_KEY, request(enrollmentId));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var result = (GeoScoreResult) rabbitTemplate.receiveAndConvert(RESULT_CAPTURE_QUEUE, 100);
            assertThat(result).isNotNull();
            assertThat(result.enrollmentId()).isEqualTo(enrollmentId);
            assertThat(result.neighborCounts()).isNotNull();
        });
    }

    @Test
    void handleGeoScoreRequest_preservesEnrollmentId() {
        var enrollmentId = UUID.randomUUID();
        rabbitTemplate.convertAndSend(REQUEST_EXCHANGE, GEO_KEY, request(enrollmentId));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var result = (GeoScoreResult) rabbitTemplate.receiveAndConvert(RESULT_CAPTURE_QUEUE, 100);
            assertThat(result).isNotNull();
            assertThat(result.enrollmentId()).isEqualTo(enrollmentId);
        });
    }

    @Test
    void redeliveredRequest_isAnsweredWithTheResultOfItsFirstRun() {
        var enrollmentId = UUID.randomUUID();

        rabbitTemplate.convertAndSend(REQUEST_EXCHANGE, GEO_KEY, request(enrollmentId));
        var first = awaitResult(enrollmentId);

        // Another enrollment at the same address raises the density in between.
        var other = UUID.randomUUID();
        rabbitTemplate.convertAndSend(REQUEST_EXCHANGE, GEO_KEY, request(other));
        var between = awaitResult(other);

        rabbitTemplate.convertAndSend(REQUEST_EXCHANGE, GEO_KEY, request(enrollmentId));
        var repeated = awaitResult(enrollmentId);

        assertThat(first.riskLevel()).isNotNull();
        assertThat(between.neighborCounts().get(100)).isEqualTo(first.neighborCounts().get(100) + 1);
        assertThat(repeated).isEqualTo(first);
    }

    @Test
    void poisonPill_routedToDeadLetterQueue() {
        var poison = MessageBuilder.withBody("{\"garbage\": true}".getBytes())
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
        rabbitTemplate.send(REQUEST_EXCHANGE, GEO_KEY, poison);

        // Well inside the 7 s the retry backoff adds up to (1 s + 2 s + 4 s): a message that
        // cannot be converted is dead-lettered on the first throw.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var dlqMessage = rabbitTemplate.receive(REQUEST_DLQ, 100);
            assertThat(dlqMessage).isNotNull();
            assertThat(new String(dlqMessage.getBody())).contains("garbage");
        });
    }

    @Test
    void transientFailure_retriedAndSucceeds() {
        doThrow(new RuntimeException("transient failure"))
                .doCallRealMethod()
                .when(listener).handleGeoScoreRequest(any());

        var enrollmentId = UUID.randomUUID();
        rabbitTemplate.convertAndSend(REQUEST_EXCHANGE, GEO_KEY, request(enrollmentId));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var result = (GeoScoreResult) rabbitTemplate.receiveAndConvert(RESULT_CAPTURE_QUEUE, 100);
            assertThat(result).isNotNull();
            assertThat(result.enrollmentId()).isEqualTo(enrollmentId);
        });

        assertThat(rabbitTemplate.receive(REQUEST_DLQ, 200)).isNull();
    }

    @Test
    void geocodingFailure_emitsNullRiskLevelWithReason() {
        doReturn(Optional.empty()).when(geocodingService).resolve(any());

        var enrollmentId = UUID.randomUUID();
        rabbitTemplate.convertAndSend(REQUEST_EXCHANGE, GEO_KEY, request(enrollmentId));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var result = (GeoScoreResult) rabbitTemplate.receiveAndConvert(RESULT_CAPTURE_QUEUE, 100);
            assertThat(result).isNotNull();
            assertThat(result.enrollmentId()).isEqualTo(enrollmentId);
            assertThat(result.riskLevel()).isNull();
            assertThat(result.noResultReason()).isEqualTo("geocoding_failed");
        });
    }

    private GeoScoreResult awaitResult(UUID enrollmentId) {
        var captured = new AtomicReference<GeoScoreResult>();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var result = (GeoScoreResult) rabbitTemplate.receiveAndConvert(RESULT_CAPTURE_QUEUE, 100);
            assertThat(result).isNotNull();
            assertThat(result.enrollmentId()).isEqualTo(enrollmentId);
            captured.set(result);
        });
        return captured.get();
    }

    private static GeoScoreRequest request(UUID enrollmentId) {
        var address = new Address(List.of("Avenue de Monte-Carlo"), "98000", "Monaco", null, "MC");
        return new GeoScoreRequest(enrollmentId, address);
    }

    @TestConfiguration
    static class RequestTopology {

        @Bean
        DirectExchange testCheckRequestExchange() {
            return new DirectExchange(REQUEST_EXCHANGE, true, false);
        }

        @Bean
        DirectExchange testGeoRequestDlx() {
            return new DirectExchange(REQUEST_DLX, true, false);
        }

        @Bean
        Queue testGeoRequestQueue() {
            return QueueBuilder.durable(REQUEST_QUEUE)
                    .deadLetterExchange(REQUEST_DLX)
                    .deadLetterRoutingKey(REQUEST_DLQ_RK)
                    .build();
        }

        @Bean
        Queue testGeoRequestDlq() {
            return QueueBuilder.durable(REQUEST_DLQ).build();
        }

        @Bean
        Binding testGeoRequestBinding() {
            return BindingBuilder.bind(testGeoRequestQueue()).to(testCheckRequestExchange()).with(GEO_KEY);
        }

        @Bean
        Binding testGeoRequestDlqBinding() {
            return BindingBuilder.bind(testGeoRequestDlq()).to(testGeoRequestDlx()).with(REQUEST_DLQ_RK);
        }

        @Bean
        Queue testResultCaptureQueue() {
            return QueueBuilder.nonDurable(RESULT_CAPTURE_QUEUE).build();
        }

        @Bean
        Binding testResultCaptureBinding() {
            return BindingBuilder.bind(testResultCaptureQueue())
                    .to(new DirectExchange(RESULT_EXCHANGE, true, false)).with(GEO_KEY);
        }
    }
}
