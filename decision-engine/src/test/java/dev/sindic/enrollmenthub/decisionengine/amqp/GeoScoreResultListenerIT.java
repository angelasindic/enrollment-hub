package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentDecisionEvent;
import dev.sindic.enrollmenthub.contracts.events.GeoScoreResult;
import dev.sindic.enrollmenthub.decisionengine.BaseIntegrationTest;
import dev.sindic.enrollmenthub.decisionengine.domain.*;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import dev.sindic.enrollmenthub.decisionengine.service.SignalMapJson;
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
                Map.of(100, 5, 250, 12), List.of(100, 250));

        rabbitTemplate.convertAndSend(AmqpConfig.CHECK_RESULT_EXCHANGE, AmqpConfig.GEO_SCORE_KEY, event);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var entity = repository.findById(enrollmentId).orElseThrow();
            assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE))
                    .isEqualTo(new SignalState.Scored(RiskLevel.HIGH));
        });
    }

    @Test
    void duplicateDelivery_isIdempotentNoOp() {
        var enrollmentId = UUID.randomUUID();
        seedCreditCardRequest(enrollmentId);

        var event = new GeoScoreResult(
                enrollmentId, C_LOW, null,
                Map.of(), List.of());

        rabbitTemplate.convertAndSend(AmqpConfig.CHECK_RESULT_EXCHANGE, AmqpConfig.GEO_SCORE_KEY, event);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var entity = repository.findById(enrollmentId).orElseThrow();
            assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE))
                    .isEqualTo(new SignalState.Scored(RiskLevel.LOW));
        });

        var duplicate = new GeoScoreResult(
                enrollmentId, C_HIGH, null,
                Map.of(100, 99), List.of(100));
        rabbitTemplate.convertAndSend(AmqpConfig.CHECK_RESULT_EXCHANGE, AmqpConfig.GEO_SCORE_KEY, duplicate);

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var entity = repository.findById(enrollmentId).orElseThrow();
            assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE))
                    .isEqualTo(new SignalState.Scored(RiskLevel.LOW));
        });
    }

    @Test
    void unknownEnrollmentId_routedToDeadLetterQueue() {
        var unknownId = UUID.randomUUID();
        var event = new GeoScoreResult(
                unknownId, C_HIGH, null,
                Map.of(), List.of());

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

        // Non-exclusive, non-auto-delete: awaitDecisionFor subscribes and cancels repeatedly
        // while draining, which would delete an auto-delete queue mid-drain.
        var captureQueueName = "test.decision.capture." + enrollmentId;
        amqpAdmin.declareQueue(new Queue(captureQueueName, false, false, false));
        amqpAdmin.declareBinding(new Binding(captureQueueName, Binding.DestinationType.QUEUE,
                AmqpConfig.DECISION_EXCHANGE, AmqpConfig.DECISION_ROUTING_KEY, null));
        try {
            txTemplate.executeWithoutResult(status -> {
                var entity = repository.saveAndFlush(
                        TestEntityFactory.creditCard(enrollmentId, Instant.now(), Instant.now().plusSeconds(60)));
                // Seed FRAUD_CHECK as already-settled via the production write path
                // (ADR-16 §Write path). The incoming GeoScoreResult then completes the row.
                var seedSignals = new EnumMap<>(entity.getSignals());
                seedSignals.put(SignalConfig.FRAUD_CHECK, new SignalState.Checked(SignalOutcome.OK));
                repository.updateSignals(enrollmentId, SignalMapJson.write(jsonMapper, seedSignals));
            });

            var event = new GeoScoreResult(
                    enrollmentId, C_LOW, null,
                    Map.of(100, 1), List.of());

            rabbitTemplate.convertAndSend(AmqpConfig.CHECK_RESULT_EXCHANGE, AmqpConfig.GEO_SCORE_KEY, event);

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                var entity = repository.findById(enrollmentId).orElseThrow();
                assertThat(entity.getDecisionResult()).isEqualTo(DecisionResult.APPROVED);
                assertThat(entity.getDecidedAt()).isNotNull();
            });

            // The decisions exchange is shared: the background sweep may finalize other suites'
            // expired rows and publish their (timeout-FAILED) decisions into this capture queue.
            // Match on this row's persisted decisionId instead of taking the first message.
            var decisionId = repository.findById(enrollmentId).orElseThrow().getDecisionId();
            var decision = awaitDecisionFor(captureQueueName, decisionId);
            assertThat(decision.originalRequest()).isNotNull();
            assertThat(decision.decisionResult())
                    .isEqualTo(dev.sindic.enrollmenthub.contracts.events.DecisionResult.APPROVED);
            assertThat(decision.signals().get("GEO_SCORE").riskLevel())
                    .isEqualTo(dev.sindic.enrollmenthub.contracts.events.RiskLevel.LOW);
            assertThat(decision.signals().get("FRAUD_CHECK").outcome())
                    .isEqualTo(dev.sindic.enrollmenthub.contracts.events.SignalOutcome.OK);
            assertThat(decision.decidedAt()).isNotNull();
        } finally {
            amqpAdmin.deleteQueue(captureQueueName);
        }
    }

    /** Drains the capture queue until the decision carrying {@code decisionId} appears. */
    private EnrollmentDecisionEvent awaitDecisionFor(String queue, UUID decisionId) {
        var deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            var received = rabbitTemplate.receiveAndConvert(queue, 200,
                    new ParameterizedTypeReference<EnrollmentDecisionEvent>() {});
            if (received != null && received.decisionId().equals(decisionId)) {
                return received;
            }
        }
        throw new AssertionError("No EnrollmentDecisionEvent for decisionId=" + decisionId + " within timeout");
    }

    @Test
    void geoScoreWithNullRiskLevel_settlesWithoutResult() {
        var enrollmentId = UUID.randomUUID();
        seedCreditCardRequest(enrollmentId);

        var event = new GeoScoreResult(
                enrollmentId, null, "geocoding_failed",
                Map.of(), List.of());

        rabbitTemplate.convertAndSend(AmqpConfig.CHECK_RESULT_EXCHANGE, AmqpConfig.GEO_SCORE_KEY, event);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var entity = repository.findById(enrollmentId).orElseThrow();
            var geoState = entity.getSignals().get(SignalConfig.GEO_SCORE);
            // Ran, could not score — NoResult, not NotExecuted. ADR-14 keeps these distinct.
            assertThat(geoState).isEqualTo(new SignalState.NoResult("geocoding_failed"));
        });
    }

    @Test
    void dlqDepthGauge_reflectsDlqDepth() {
        amqpAdmin.purgeQueue(AmqpConfig.GEO_SCORE_RESULT_DLQ);

        var unknownId = UUID.randomUUID();
        var event = new GeoScoreResult(
                unknownId, C_HIGH, null,
                Map.of(), List.of());
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
