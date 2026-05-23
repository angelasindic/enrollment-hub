package dev.sindic.enrollmenthub.geoscoring.amqp;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import dev.sindic.enrollmenthub.contracts.domain.Person;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentEvent;
import dev.sindic.enrollmenthub.contracts.events.GeoScoreResult;
import dev.sindic.enrollmenthub.geoscoring.BaseIntegrationTest;
import dev.sindic.enrollmenthub.geoscoring.service.GeocodingService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class EnrollmentAcceptedListenerIT extends BaseIntegrationTest {

    private static final String RESULT_CAPTURE_QUEUE = "test.result.capture";

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired MeterRegistry meterRegistry;
    @Autowired TopicExchange enrollmentExchange;

    @MockitoSpyBean EnrollmentAcceptedListener listener;
    @MockitoSpyBean GeocodingService geocodingService;

    @BeforeEach
    void setUpResultCaptureQueue() {
        var queue = QueueBuilder.nonDurable(RESULT_CAPTURE_QUEUE).build();
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(
                BindingBuilder.bind(queue).to(enrollmentExchange).with(AmqpConfig.RESULT_ROUTING_KEY));
    }

    @Test
    void handleEnrollmentAccepted_publishesGeoScoreResult() {
        var enrollmentId = UUID.randomUUID();
        rabbitTemplate.convertAndSend(AmqpConfig.EXCHANGE, AmqpConfig.ROUTING_KEY,
                new EnrollmentEvent(Instant.now(), enrollmentData("MC", enrollmentId)));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var result = (GeoScoreResult) rabbitTemplate.receiveAndConvert(RESULT_CAPTURE_QUEUE, 100);
            assertThat(result).isNotNull();
            assertThat(result.requestId()).isEqualTo(enrollmentId);
            assertThat(result.neighborCounts()).isNotNull();
        });
    }

    @Test
    void handleEnrollmentAccepted_preservesCorrelationId() {
        var enrollmentId = UUID.randomUUID();
        rabbitTemplate.convertAndSend(AmqpConfig.EXCHANGE, AmqpConfig.ROUTING_KEY,
                new EnrollmentEvent(Instant.now(), enrollmentData("MC", enrollmentId)));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var result = (GeoScoreResult) rabbitTemplate.receiveAndConvert(RESULT_CAPTURE_QUEUE, 100);
            assertThat(result).isNotNull();
            assertThat(result.requestId()).isEqualTo(enrollmentId);
        });
    }

    @Test
    void handleEnrollmentAccepted_invoiceRouteNotDelivered() {
        rabbitTemplate.convertAndSend(AmqpConfig.EXCHANGE, "enrollment.created.invoice",
                new EnrollmentEvent(Instant.now(), enrollmentData("MC", UUID.randomUUID())));

        verify(listener, after(500).never()).handleEnrollmentAccepted(any());
    }

    @Test
    void poisonPill_routedToDeadLetterQueue() {
        var poison = MessageBuilder
                .withBody("{\"garbage\": true}".getBytes())
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
        rabbitTemplate.send(AmqpConfig.EXCHANGE, AmqpConfig.ROUTING_KEY, poison);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var dlqMessage = rabbitTemplate.receive(AmqpConfig.DLQ, 100);
            assertThat(dlqMessage).isNotNull();
            assertThat(new String(dlqMessage.getBody())).contains("garbage");
        });
    }

    @Test
    void transientFailure_retriedAndSucceeds() {
        doThrow(new RuntimeException("transient failure"))
                .doCallRealMethod()
                .when(listener).handleEnrollmentAccepted(any());

        var enrollmentId = UUID.randomUUID();
        rabbitTemplate.convertAndSend(AmqpConfig.EXCHANGE, AmqpConfig.ROUTING_KEY,
                new EnrollmentEvent(Instant.now(), enrollmentData("MC", enrollmentId)));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var result = (GeoScoreResult) rabbitTemplate.receiveAndConvert(RESULT_CAPTURE_QUEUE, 100);
            assertThat(result).isNotNull();
            assertThat(result.requestId()).isEqualTo(enrollmentId);
        });

        var dlqMessage = rabbitTemplate.receive(AmqpConfig.DLQ, 200);
        assertThat(dlqMessage).isNull();
    }

    @Test
    void geocodingFailure_emitsNullRiskLevelWithReason() {
        doReturn(Optional.empty()).when(geocodingService).resolve(any());

        var enrollmentId = UUID.randomUUID();
        rabbitTemplate.convertAndSend(AmqpConfig.EXCHANGE, AmqpConfig.ROUTING_KEY,
                new EnrollmentEvent(Instant.now(), enrollmentData("MC", enrollmentId)));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var result = (GeoScoreResult) rabbitTemplate.receiveAndConvert(RESULT_CAPTURE_QUEUE, 100);
            assertThat(result).isNotNull();
            assertThat(result.requestId()).isEqualTo(enrollmentId);
            assertThat(result.riskLevel()).isNull();
            assertThat(result.noResultReason()).isEqualTo("geocoding_failed");
            assertThat(result.latitude()).isNull();
            assertThat(result.longitude()).isNull();
            assertThat(result.neighborCounts()).isEmpty();
            assertThat(result.triggeredThresholds()).isEmpty();
        });
    }

    @Test
    void dlqDepthGauge_reflectsDlqDepth() {
        rabbitAdmin.purgeQueue(AmqpConfig.DLQ, false);

        var poison = MessageBuilder
                .withBody("{\"garbage\": true}".getBytes())
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
        rabbitTemplate.send(AmqpConfig.EXCHANGE, AmqpConfig.ROUTING_KEY, poison);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var gauge = meterRegistry.find("rabbitmq.dlq.depth").tag("queue", AmqpConfig.DLQ).gauge();
            assertThat(gauge).isNotNull();
            assertThat(gauge.value()).isGreaterThan(0);
        });

        rabbitAdmin.purgeQueue(AmqpConfig.DLQ, false);
    }

    private static EnrollmentData enrollmentData(String countryCode, UUID id) {
        var person = new Person("Jane", "Doe", "jane@example.com", "+491234567890");
        var address = new Address(List.of("Avenue de Monte-Carlo"), "98000", "Monaco", null, countryCode);
        return new EnrollmentData(id, PaymentType.CREDIT_CARD, person, address, address);
    }
}
