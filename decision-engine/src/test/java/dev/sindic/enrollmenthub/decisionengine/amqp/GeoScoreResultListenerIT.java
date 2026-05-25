package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentDecisionEvent;
import dev.sindic.enrollmenthub.contracts.events.GeoScoreResult;
import dev.sindic.enrollmenthub.decisionengine.BaseIntegrationTest;
import dev.sindic.enrollmenthub.decisionengine.domain.*;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import dev.sindic.enrollmenthub.decisionengine.TestEntityFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.util.EnumMap;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Shorthand for the contracts RiskLevel used when constructing GeoScoreResult AMQP events.
// Assertions against entity.getSignals()...riskLevel() use the domain RiskLevel (from wildcard import).
class GeoScoreResultListenerIT extends BaseIntegrationTest {

    private static final dev.sindic.enrollmenthub.contracts.events.RiskLevel C_HIGH =
            dev.sindic.enrollmenthub.contracts.events.RiskLevel.HIGH;
    private static final dev.sindic.enrollmenthub.contracts.events.RiskLevel C_LOW =
            dev.sindic.enrollmenthub.contracts.events.RiskLevel.LOW;

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired EnrollmentRepository repository;
    @Autowired TransactionTemplate txTemplate;
    @Autowired AmqpAdmin amqpAdmin;
    @Autowired MeterRegistry meterRegistry;
    @Autowired JsonMapper jsonMapper;

    @Test
    void handleGeoScoreResult_updatesCorrelationRecord() {
        var enrollmentId = UUID.randomUUID();
        seedCreditCardRequest(enrollmentId);

        var event = new GeoScoreResult(
                enrollmentId, C_HIGH, null,
                Map.of(100, 5, 250, 12), List.of(100, 250),
                48.8566, 2.3522);

        rabbitTemplate.convertAndSend(AmqpConfig.CHECK_RESULT_EXCHANGE, AmqpConfig.GEO_SCORE_KEY, event);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var entity = repository.findById(enrollmentId).orElseThrow();
            assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE).processingState())
                    .isEqualTo(SignalProcessingState.SETTLED);
            assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE).riskLevel())
                    .isEqualTo(RiskLevel.HIGH);
        });
    }

    @Test
    void duplicateDelivery_isIdempotentNoOp() {
        var enrollmentId = UUID.randomUUID();
        seedCreditCardRequest(enrollmentId);

        var event = new GeoScoreResult(
                enrollmentId, C_LOW, null,
                Map.of(), List.of(), 52.52, 13.405);

        rabbitTemplate.convertAndSend(AmqpConfig.CHECK_RESULT_EXCHANGE, AmqpConfig.GEO_SCORE_KEY, event);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var entity = repository.findById(enrollmentId).orElseThrow();
            assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE).riskLevel())
                    .isEqualTo(RiskLevel.LOW);
        });

        var duplicate = new GeoScoreResult(
                enrollmentId, C_HIGH, null,
                Map.of(100, 99), List.of(100), 52.52, 13.405);
        rabbitTemplate.convertAndSend(AmqpConfig.CHECK_RESULT_EXCHANGE, AmqpConfig.GEO_SCORE_KEY, duplicate);

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var entity = repository.findById(enrollmentId).orElseThrow();
            assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE).riskLevel())
                    .isEqualTo(RiskLevel.LOW);
        });
    }

    @Test
    void unknownEnrollmentId_routedToDeadLetterQueue() {
        var unknownId = UUID.randomUUID();
        var event = new GeoScoreResult(
                unknownId, C_HIGH, null,
                Map.of(), List.of(), 48.8566, 2.3522);

        rabbitTemplate.convertAndSend(AmqpConfig.CHECK_RESULT_EXCHANGE, AmqpConfig.GEO_SCORE_KEY, event);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var dlqMessage = rabbitTemplate.receive(AmqpConfig.GEO_SCORE_RESULT_DLQ, 100);
            assertThat(dlqMessage).isNotNull();
        });
        assertThat(repository.findById(unknownId)).isEmpty();
    }

    @Test
    void geoScoreAsLastSignal_triggersDecisionEngine() {
        var enrollmentId = UUID.randomUUID();

        var captureQueueName = "test.decision.capture." + enrollmentId;
        amqpAdmin.declareQueue(new Queue(captureQueueName, false, true, true));
        amqpAdmin.declareBinding(new Binding(captureQueueName, Binding.DestinationType.QUEUE,
                AmqpConfig.DECISION_EXCHANGE, AmqpConfig.DECISION_ROUTING_KEY, null));

        txTemplate.executeWithoutResult(status -> {
            var entity = repository.saveAndFlush(
                    TestEntityFactory.creditCard(enrollmentId, Instant.now(), Instant.now().plusSeconds(60)));
            // Seed FRAUD_CHECK as already-settled via the production write path
            // (ADR-015 §Write path). The incoming GeoScoreResult then completes the row.
            var seedSignals = new EnumMap<>(entity.getSignals());
            seedSignals.put(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));
            repository.updateSignals(enrollmentId, jsonMapper.writeValueAsString(seedSignals));
        });

        var event = new GeoScoreResult(
                enrollmentId, C_LOW, null,
                Map.of(100, 1), List.of(), 52.52, 13.405);

        rabbitTemplate.convertAndSend(AmqpConfig.CHECK_RESULT_EXCHANGE, AmqpConfig.GEO_SCORE_KEY, event);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var entity = repository.findById(enrollmentId).orElseThrow();
            assertThat(entity.getDecisionResult()).isEqualTo(DecisionResult.APPROVED);
            assertThat(entity.getDecidedAt()).isNotNull();
        });

        var decision = rabbitTemplate.receiveAndConvert(captureQueueName, 2_000,
                new ParameterizedTypeReference<EnrollmentDecisionEvent>() {});
        assertThat(decision).isNotNull();
        assertThat(decision.decisionId()).isNotNull();
        assertThat(decision.originalRequest()).isNotNull();
        assertThat(decision.decisionResult())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.events.DecisionResult.APPROVED);
        assertThat(decision.signals().get("GEO_SCORE").riskLevel())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.events.RiskLevel.LOW);
        assertThat(decision.signals().get("FRAUD_CHECK").outcome())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.events.SignalOutcome.OK);
        assertThat(decision.decidedAt()).isNotNull();
    }

    @Test
    void geoScoreWithNullRiskLevel_settlesWithoutResult() {
        var enrollmentId = UUID.randomUUID();
        seedCreditCardRequest(enrollmentId);

        var event = new GeoScoreResult(
                enrollmentId, null, "geocoding_failed",
                Map.of(), List.of(), null, null);

        rabbitTemplate.convertAndSend(AmqpConfig.CHECK_RESULT_EXCHANGE, AmqpConfig.GEO_SCORE_KEY, event);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var entity = repository.findById(enrollmentId).orElseThrow();
            var geoState = entity.getSignals().get(SignalConfig.GEO_SCORE);
            assertThat(geoState.processingState()).isEqualTo(SignalProcessingState.SETTLED);
            assertThat(geoState.riskLevel()).isNull();
            assertThat(geoState.reason()).isEqualTo("geocoding_failed");
        });
    }

    @Test
    void dlqDepthGauge_reflectsDlqDepth() {
        amqpAdmin.purgeQueue(AmqpConfig.GEO_SCORE_RESULT_DLQ);

        var unknownId = UUID.randomUUID();
        var event = new GeoScoreResult(
                unknownId, C_HIGH, null,
                Map.of(), List.of(), 48.8566, 2.3522);
        rabbitTemplate.convertAndSend(AmqpConfig.CHECK_RESULT_EXCHANGE, AmqpConfig.GEO_SCORE_KEY, event);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var gauge = meterRegistry.find("rabbitmq.dlq.depth")
                    .tag("queue", AmqpConfig.GEO_SCORE_RESULT_DLQ).gauge();
            assertThat(gauge).isNotNull();
            assertThat(gauge.value()).isGreaterThan(0);
        });

        amqpAdmin.purgeQueue(AmqpConfig.GEO_SCORE_RESULT_DLQ);
    }

    private void seedCreditCardRequest(UUID enrollmentId) {
        var entity = TestEntityFactory.creditCard(enrollmentId, Instant.now(), Instant.now().plusSeconds(60));
        repository.saveAndFlush(entity);
    }
}
