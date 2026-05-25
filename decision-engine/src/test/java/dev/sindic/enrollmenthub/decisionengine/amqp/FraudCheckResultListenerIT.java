package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentDecisionEvent;
import dev.sindic.enrollmenthub.contracts.events.FraudCheckResult;
import dev.sindic.enrollmenthub.decisionengine.BaseIntegrationTest;
import dev.sindic.enrollmenthub.decisionengine.TestEntityFactory;
import dev.sindic.enrollmenthub.decisionengine.domain.*;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
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
            assertThat(entity.getSignals().get(SignalConfig.FRAUD_CHECK).processingState())
                    .isEqualTo(SignalProcessingState.SETTLED);
            assertThat(entity.getSignals().get(SignalConfig.FRAUD_CHECK).outcome())
                    .isEqualTo(SignalOutcome.OK);
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

        var captureQueueName = "test.decision.capture.fraud." + enrollmentId;
        amqpAdmin.declareQueue(new Queue(captureQueueName, false, true, true));
        amqpAdmin.declareBinding(new Binding(captureQueueName, Binding.DestinationType.QUEUE,
                AmqpConfig.DECISION_EXCHANGE, AmqpConfig.DECISION_ROUTING_KEY, null));

        txTemplate.executeWithoutResult(status -> {
            var entity = repository.saveAndFlush(
                    TestEntityFactory.creditCard(enrollmentId, Instant.now(), Instant.now().plusSeconds(60)));
            // Seed GEO_SCORE as already-settled; the incoming FraudCheckResult then completes the row.
            var seedSignals = new EnumMap<>(entity.getSignals());
            seedSignals.put(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.LOW));
            repository.updateSignals(enrollmentId, jsonMapper.writeValueAsString(seedSignals));
        });

        rabbitTemplate.convertAndSend(AmqpConfig.CHECK_RESULT_EXCHANGE, AmqpConfig.FRAUD_CHECK_KEY,
                new FraudCheckResult(enrollmentId, C_OK));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var entity = repository.findById(enrollmentId).orElseThrow();
            assertThat(entity.getDecisionResult()).isEqualTo(DecisionResult.APPROVED);
            assertThat(entity.getDecidedAt()).isNotNull();
        });

        var decision = rabbitTemplate.receiveAndConvert(captureQueueName, 2_000,
                new ParameterizedTypeReference<EnrollmentDecisionEvent>() {});
        assertThat(decision).isNotNull();
        assertThat(decision.decisionResult())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.events.DecisionResult.APPROVED);
        assertThat(decision.signals().get("FRAUD_CHECK").outcome())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.events.SignalOutcome.OK);
    }

    private void seedCreditCardRequest(UUID enrollmentId) {
        var entity = TestEntityFactory.creditCard(enrollmentId, Instant.now(), Instant.now().plusSeconds(60));
        repository.saveAndFlush(entity);
    }
}
