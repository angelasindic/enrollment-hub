package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentDecisionEvent;
import dev.sindic.enrollmenthub.contracts.events.FraudCheckResult;
import dev.sindic.enrollmenthub.decisionengine.BaseIntegrationTest;
import dev.sindic.enrollmenthub.decisionengine.TestEntityFactory;
import dev.sindic.enrollmenthub.decisionengine.domain.*;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import dev.sindic.enrollmenthub.decisionengine.service.SignalMapJson;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Shorthand for the contracts SignalOutcome used when constructing FraudCheckResult AMQP events.
// Assertions against entity.getSignals()...outcome() use the domain SignalOutcome (wildcard import).
class FraudCheckResultListenerIT extends BaseIntegrationTest {

    private static final dev.sindic.enrollmenthub.contracts.events.SignalOutcome C_OK =
            dev.sindic.enrollmenthub.contracts.events.SignalOutcome.OK;

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired EnrollmentRepository repository;
    @Autowired TransactionTemplate txTemplate;
    @Autowired AmqpAdmin amqpAdmin;
    @Autowired JsonMapper jsonMapper;

    @Test
    void handleFraudCheckResult_updatesCorrelationRecord() {
        var enrollmentId = UUID.randomUUID();
        seedCreditCardRequest(enrollmentId);

        rabbitTemplate.convertAndSend(AmqpConfig.CHECK_RESULT_EXCHANGE, AmqpConfig.FRAUD_CHECK_KEY,
                new FraudCheckResult(enrollmentId, C_OK));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var entity = repository.findById(enrollmentId).orElseThrow();
            assertThat(entity.getSignals().get(SignalConfig.FRAUD_CHECK))
                    .isEqualTo(new SignalState.Checked(SignalOutcome.OK));
        });
    }

    @Test
    void unknownEnrollmentId_routedToDeadLetterQueue() {
        var unknownId = UUID.randomUUID();

        rabbitTemplate.convertAndSend(AmqpConfig.CHECK_RESULT_EXCHANGE, AmqpConfig.FRAUD_CHECK_KEY,
                new FraudCheckResult(unknownId, C_OK));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var dlqMessage = rabbitTemplate.receive(AmqpConfig.FRAUD_CHECK_RESULT_DLQ, 100);
            assertThat(dlqMessage).isNotNull();
        });
        assertThat(repository.findById(unknownId)).isEmpty();
    }

    @Test
    void fraudAsLastSignal_triggersDecisionEngine() {
        var enrollmentId = UUID.randomUUID();

        // Non-exclusive, non-auto-delete: awaitDecisionFor subscribes and cancels repeatedly
        // while draining, which would delete an auto-delete queue mid-drain.
        var captureQueueName = "test.decision.capture.fraud." + enrollmentId;
        amqpAdmin.declareQueue(new Queue(captureQueueName, false, false, false));
        amqpAdmin.declareBinding(new Binding(captureQueueName, Binding.DestinationType.QUEUE,
                AmqpConfig.DECISION_EXCHANGE, AmqpConfig.DECISION_ROUTING_KEY, null));
        try {
            txTemplate.executeWithoutResult(status -> {
                var entity = repository.saveAndFlush(
                        TestEntityFactory.creditCard(enrollmentId, Instant.now(), Instant.now().plusSeconds(60)));
                // Seed GEO_SCORE as already-settled; the incoming FraudCheckResult then completes the row.
                var seedSignals = new EnumMap<>(entity.getSignals());
                seedSignals.put(SignalConfig.GEO_SCORE, new SignalState.Scored(RiskLevel.LOW));
                repository.updateSignals(enrollmentId, SignalMapJson.write(jsonMapper, seedSignals));
            });

            rabbitTemplate.convertAndSend(AmqpConfig.CHECK_RESULT_EXCHANGE, AmqpConfig.FRAUD_CHECK_KEY,
                    new FraudCheckResult(enrollmentId, C_OK));

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
            assertThat(decision.decisionResult())
                    .isEqualTo(dev.sindic.enrollmenthub.contracts.events.DecisionResult.APPROVED);
            assertThat(decision.signals().get("FRAUD_CHECK").outcome())
                    .isEqualTo(dev.sindic.enrollmenthub.contracts.events.SignalOutcome.OK);
        } finally {
            amqpAdmin.deleteQueue(captureQueueName);
        }
    }

    /** Drains the capture queue until the decision carrying {@code decisionId} appears. */
    private EnrollmentDecisionEvent awaitDecisionFor(String queue, UUID decisionId) {
        var deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            var event = rabbitTemplate.receiveAndConvert(queue, 200,
                    new ParameterizedTypeReference<EnrollmentDecisionEvent>() {});
            if (event != null && event.decisionId().equals(decisionId)) {
                return event;
            }
        }
        throw new AssertionError("No EnrollmentDecisionEvent for decisionId=" + decisionId + " within timeout");
    }

    private void seedCreditCardRequest(UUID enrollmentId) {
        var entity = TestEntityFactory.creditCard(enrollmentId, Instant.now(), Instant.now().plusSeconds(60));
        repository.saveAndFlush(entity);
    }
}
