package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentDecisionEvent;
import dev.sindic.enrollmenthub.decisionengine.BaseIntegrationTest;
import dev.sindic.enrollmenthub.decisionengine.TestEntityFactory;
import dev.sindic.enrollmenthub.decisionengine.domain.*;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.sindic.enrollmenthub.decisionengine.amqp.AmqpConfig.DECISION_EXCHANGE;
import static dev.sindic.enrollmenthub.decisionengine.amqp.AmqpConfig.DECISION_ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the timeout-poller finalize path (ADR-15), driven directly through
 * {@link EnrollmentService#processExpiredTimeouts} against Testcontainers Postgres + RabbitMQ.
 *
 * <p>The background scheduler is pushed an hour out so only the explicit calls below finalize rows —
 * this keeps the multi-step seeds (save, then settle a signal) race-free. Because the test database
 * is shared across the suite, a single {@code processExpiredTimeouts} call may also finalize other
 * suites' expired rows, so the capture queue is drained until the decision carrying this test's
 * enrollmentId appears.
 */
@TestPropertySource(properties = "decision-engine.timeout-poller.interval=PT1H")
class TimeoutPollerIT extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<EnrollmentDecisionEvent> DECISION_TYPE =
            new ParameterizedTypeReference<>() {};

    @Autowired EnrollmentService enrollmentService;
    @Autowired EnrollmentRepository repository;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;

    private final List<String> declaredQueues = new ArrayList<>();

    @AfterEach
    void cleanUpCaptureQueues() {
        declaredQueues.forEach(amqpAdmin::deleteQueue);
        declaredQueues.clear();
    }

    @Test
    void expiredEnrollment_allSignalsPending_finalizesApproved_andPublishes() {
        var id = UUID.randomUUID();
        var capture = bindCaptureQueue(id);
        repository.saveAndFlush(TestEntityFactory.creditCard(id,
                Instant.now().minusSeconds(120), Instant.now().minusSeconds(60)));

        enrollmentService.processExpiredTimeouts(Instant.now(), 100);

        var entity = repository.findById(id).orElseThrow();
        assertThat(entity.getDecisionResult()).isEqualTo(DecisionResult.APPROVED);
        assertThat(entity.getDecidedAt()).isNotNull();
        assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE).processingState())
                .isEqualTo(SignalProcessingState.FAILED);
        assertThat(entity.getSignals().get(SignalConfig.FRAUD_CHECK).processingState())
                .isEqualTo(SignalProcessingState.FAILED);

        var decision = awaitDecisionFor(capture, id);
        assertThat(decision.decisionResult())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.events.DecisionResult.APPROVED);
        assertThat(decision.decisionId()).isNotNull();
        assertThat(decision.decidedAt()).isNotNull();
    }

    @Test
    void expiredEnrollment_partiallySettled_timesOutRemaining_preservingSettledResult() {
        var id = UUID.randomUUID();
        var capture = bindCaptureQueue(id);
        repository.saveAndFlush(TestEntityFactory.creditCard(id,
                Instant.now().minusSeconds(120), Instant.now().minusSeconds(60)));
        // Settle GEO_SCORE = HIGH via the production write path; leave FRAUD_CHECK pending.
        enrollmentService.recordSignalResult(id, SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.HIGH));

        enrollmentService.processExpiredTimeouts(Instant.now(), 100);

        var entity = repository.findById(id).orElseThrow();
        // A settled HIGH scoring signal flags review; the timed-out fraud check fails open.
        assertThat(entity.getDecisionResult()).isEqualTo(DecisionResult.CONDITIONAL_APPROVED);
        var geo = entity.getSignals().get(SignalConfig.GEO_SCORE);
        assertThat(geo.processingState()).isEqualTo(SignalProcessingState.SETTLED);
        assertThat(geo.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(entity.getSignals().get(SignalConfig.FRAUD_CHECK).processingState())
                .isEqualTo(SignalProcessingState.FAILED);

        var decision = awaitDecisionFor(capture, id);
        assertThat(decision.decisionResult())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.events.DecisionResult.CONDITIONAL_APPROVED);
    }

    @Test
    void notYetExpired_isNotClaimed() {
        var id = UUID.randomUUID();
        repository.saveAndFlush(TestEntityFactory.creditCard(id,
                Instant.now(), Instant.now().plusSeconds(300)));

        enrollmentService.processExpiredTimeouts(Instant.now(), 100);

        var entity = repository.findById(id).orElseThrow();
        assertThat(entity.getDecisionResult()).isNull();
        assertThat(entity.getDecidedAt()).isNull();
        assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE).processingState())
                .isEqualTo(SignalProcessingState.PENDING);
    }

    /**
     * Declares a non-exclusive, non-auto-delete capture queue bound to the decisions exchange and
     * tracks it for teardown. Non-auto-delete is essential: {@link #awaitDecisionFor} subscribes and
     * cancels repeatedly while draining, which would delete an auto-delete queue mid-drain.
     */
    private String bindCaptureQueue(UUID id) {
        var name = "test.timeout.capture." + id;
        amqpAdmin.declareQueue(new Queue(name, false, false, false));
        amqpAdmin.declareBinding(new Binding(name, Binding.DestinationType.QUEUE,
                DECISION_EXCHANGE, DECISION_ROUTING_KEY, null));
        declaredQueues.add(name);
        return name;
    }

    /**
     * Drains the capture queue until the decision carrying {@code id} appears. Decisions for any
     * other expired rows the poller finalized land on the same exchange; matching on the carried
     * enrollmentId isolates this test from them.
     */
    private EnrollmentDecisionEvent awaitDecisionFor(String queue, UUID id) {
        var deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            var event = rabbitTemplate.receiveAndConvert(queue, 200, DECISION_TYPE);
            if (event != null && event.originalRequest().enrollmentId().equals(id)) {
                return event;
            }
        }
        throw new AssertionError("No EnrollmentDecisionEvent for enrollmentId=" + id + " within timeout");
    }
}
