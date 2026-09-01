package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentDecisionEvent;
import dev.sindic.enrollmenthub.decisionengine.BaseIntegrationTest;
import dev.sindic.enrollmenthub.decisionengine.TestEntityFactory;
import dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult;
import dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalOutcome;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static dev.sindic.enrollmenthub.decisionengine.amqp.AmqpConfig.DECISION_EXCHANGE;
import static dev.sindic.enrollmenthub.decisionengine.amqp.AmqpConfig.DECISION_ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the two phases of {@link EnrollmentSweepJob} against Testcontainers
 * Postgres + RabbitMQ. The phases are driven directly through their service methods
 * ({@link EnrollmentService#processExpiredTimeouts} and {@link DecisionDispatcher#dispatchPending})
 * rather than through the scheduled {@code sweep()} tick, so the multi-step seeds stay race-free;
 * the scheduled sweep is pushed an hour out via {@code decision-engine.sweep.interval}.
 *
 * <p><b>Timeout phase (ADR-15).</b> Finalizing an expired row registers the after-commit eager
 * dispatch (ADR-17), which is what publishes the decision the assertions await — the timeout phase
 * itself does not publish.
 *
 * <p><b>Dispatch phase (ADR-17).</b> A seeded decided-but-undispatched row is exactly the
 * crash-window state ("decided, eager dispatch never happened"), so these are the routine proof of
 * the recovery backstop; the no-recompute property (the persisted {@code decisionId} is replayed,
 * never re-derived) is pinned by {@code dispatchPhase_publishesThePersistedDecision_sameDecisionId}.
 *
 * <p>The shared test database means a phase call may also process other suites' rows, so the
 * capture queue is drained until the decision carrying this test's {@code decisionId} appears.
 */
@TestPropertySource(properties = "decision-engine.sweep.interval=PT1H")
class EnrollmentSweepIT extends BaseIntegrationTest {

    @Autowired EnrollmentService enrollmentService;
    @Autowired DecisionDispatcher dispatcher;
    @Autowired EnrollmentRepository repository;
    @Autowired TransactionTemplate txTemplate;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;
    @Autowired JsonMapper jsonMapper;
    @Autowired MeterRegistry meterRegistry;

    private final List<String> declaredQueues = new ArrayList<>();

    @AfterEach
    void cleanUpCaptureQueues() {
        declaredQueues.forEach(amqpAdmin::deleteQueue);
        declaredQueues.clear();
    }

    // ── Timeout phase (ADR-15) ─────────────────────────────────────────────────

    @Test
    void timeoutPhase_allSignalsPending_finalizesApproved_andPublishes() {
        var id = UUID.randomUUID();
        var capture = bindCaptureQueue(id);
        repository.saveAndFlush(TestEntityFactory.creditCard(id,
                Instant.now().minusSeconds(120), Instant.now().minusSeconds(60)));

        enrollmentService.processExpiredTimeouts(Instant.now(), 100);

        var entity = repository.findById(id).orElseThrow();
        assertThat(entity.getDecisionResult()).isEqualTo(DecisionResult.APPROVED);
        assertThat(entity.getDecidedAt()).isNotNull();
        // Neither service replied before the deadline: never ran, so NotExecuted with the reason.
        assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE))
                .isEqualTo(new SignalState.NotExecuted("timeout"));
        assertThat(entity.getSignals().get(SignalConfig.FRAUD_CHECK))
                .isEqualTo(new SignalState.NotExecuted("timeout"));

        var decision = awaitDecisionFor(capture, entity.getDecisionId()).event();
        assertThat(decision.decisionResult())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.events.DecisionResult.APPROVED);
        assertThat(decision.decidedAt()).isNotNull();
    }

    @Test
    void timeoutPhase_partiallySettled_timesOutRemaining_preservingSettledResult() {
        var id = UUID.randomUUID();
        var capture = bindCaptureQueue(id);
        repository.saveAndFlush(TestEntityFactory.creditCard(id,
                Instant.now().minusSeconds(120), Instant.now().minusSeconds(60)));
        // Settle GEO_SCORE = HIGH via the production write path; leave FRAUD_CHECK pending.
        enrollmentService.recordSignalResult(id, SignalConfig.GEO_SCORE, new SignalState.Scored(RiskLevel.HIGH));

        enrollmentService.processExpiredTimeouts(Instant.now(), 100);

        var entity = repository.findById(id).orElseThrow();
        // A settled HIGH scoring signal flags review; the timed-out fraud check fails open.
        assertThat(entity.getDecisionResult()).isEqualTo(DecisionResult.CONDITIONAL_APPROVED);
        var geo = entity.getSignals().get(SignalConfig.GEO_SCORE);
        assertThat(geo).isEqualTo(new SignalState.Scored(RiskLevel.HIGH));
        assertThat(entity.getSignals().get(SignalConfig.FRAUD_CHECK))
                .isEqualTo(new SignalState.NotExecuted("timeout"));

        var decision = awaitDecisionFor(capture, entity.getDecisionId()).event();
        assertThat(decision.decisionResult())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.events.DecisionResult.CONDITIONAL_APPROVED);
    }

    @Test
    void timeoutPhase_notYetExpired_isNotClaimed() {
        var id = UUID.randomUUID();
        repository.saveAndFlush(TestEntityFactory.creditCard(id,
                Instant.now(), Instant.now().plusSeconds(300)));

        enrollmentService.processExpiredTimeouts(Instant.now(), 100);

        var entity = repository.findById(id).orElseThrow();
        assertThat(entity.getDecisionResult()).isNull();
        assertThat(entity.getDecidedAt()).isNull();
        assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE)).isInstanceOf(SignalState.Pending.class);
    }

    // ── Dispatch phase (ADR-17) ────────────────────────────────────────────────

    @Test
    void dispatchPhase_publishesThePersistedDecision_sameDecisionId_thenStamps() {
        var enrollmentId = UUID.randomUUID();
        var decisionId = UUID.randomUUID();
        var capture = bindCaptureQueue(enrollmentId);
        seedDecidedUndispatched(enrollmentId, decisionId);

        dispatcher.dispatchPending(50);

        // The event replays the frozen decision: the decisionId persisted at decide time,
        // not a fresh one — this is the ADR-17 no-recompute regression pin.
        var received = awaitDecisionFor(capture, decisionId);
        assertThat(received.event().decisionResult())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.events.DecisionResult.APPROVED);
        assertThat(received.event().signals().get("GEO_SCORE").riskLevel())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.events.RiskLevel.LOW);

        // The wire payload never carries the correlation primary key (dedup is on decisionId).
        assertThat(received.json())
                .doesNotContain(enrollmentId.toString())
                .doesNotContain("enrollmentId");

        // Stamp only after the publisher confirm (outbox leaves the claim set).
        assertThat(repository.findById(enrollmentId).orElseThrow().getDispatchedAt()).isNotNull();
    }

    @Test
    void dispatchPhase_dispatchedRow_isNotClaimedAgain() {
        var enrollmentId = UUID.randomUUID();
        var decisionId = UUID.randomUUID();
        var capture = bindCaptureQueue(enrollmentId);
        seedDecidedUndispatched(enrollmentId, decisionId);

        dispatcher.dispatchPending(50);
        awaitDecisionFor(capture, decisionId);

        dispatcher.dispatchPending(50);

        // A second pass must not re-publish the stamped row: no further event with this
        // decisionId arrives (a bounded drain stands in for "never").
        assertThat(receiveMatching(capture, decisionId, Instant.now().plusSeconds(2))).isNull();
    }

    @Test
    void outboxAgeGauge_tracksTheUndispatchedBacklog() {
        // The StuckDecisionOutbox alert's source signal: > 0 while a decided row awaits
        // dispatch, back to 0 once the outbox drains. Seeded 60s in the past so the
        // second-granularity gauge reads a clearly positive age.
        bindCaptureQueue(UUID.randomUUID()); // make publishes routable for the drain below
        var enrollmentId = UUID.randomUUID();
        seedDecidedUndispatched(enrollmentId, UUID.randomUUID(), Instant.now().minusSeconds(60));

        var gauge = meterRegistry.find(OutboxMetricsConfig.OUTBOX_AGE_METRIC).gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isGreaterThanOrEqualTo(59.0);

        // Drain the outbox (this row plus anything other suites left behind).
        int dispatched;
        do {
            dispatched = dispatcher.dispatchPending(50);
        } while (dispatched > 0);

        assertThat(gauge.value()).isZero();
    }

    @Test
    void dispatchPhase_markDispatched_guardRejectsASecondStamp() {
        // The dispatched_at IS NULL guard is what makes the eager/backstop race benign:
        // whichever trigger stamps second observes 0 and treats it as "already delivered".
        var enrollmentId = UUID.randomUUID();
        seedDecidedUndispatched(enrollmentId, UUID.randomUUID());

        txTemplate.executeWithoutResult(status -> {
            assertThat(repository.markDispatched(enrollmentId, Instant.now())).isEqualTo(1);
            assertThat(repository.markDispatched(enrollmentId, Instant.now())).isZero();
        });
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    /**
     * Persists a row in the outbox state ({@code decision_result NOT NULL, dispatched_at NULL})
     * through the production write path — the exact state a crash between the decide commit
     * and the eager publish leaves behind.
     */
    private void seedDecidedUndispatched(UUID enrollmentId, UUID decisionId) {
        seedDecidedUndispatched(enrollmentId, decisionId, Instant.now());
    }

    private void seedDecidedUndispatched(UUID enrollmentId, UUID decisionId, Instant decidedAt) {
        txTemplate.executeWithoutResult(status -> {
            repository.saveAndFlush(TestEntityFactory.creditCard(
                    enrollmentId, Instant.now(), Instant.now().plusSeconds(300)));
            var settled = new EnumMap<SignalConfig, SignalState>(SignalConfig.class);
            settled.put(SignalConfig.GEO_SCORE, new SignalState.Scored(RiskLevel.LOW));
            settled.put(SignalConfig.FRAUD_CHECK, new SignalState.Checked(SignalOutcome.OK));
            repository.completeWithDecision(enrollmentId,
                    SignalMapJson.write(jsonMapper, settled), "APPROVED", decisionId, decidedAt);
        });
    }

    /**
     * Declares a non-exclusive, non-auto-delete capture queue bound to the decisions exchange and
     * tracks it for teardown. Non-auto-delete is essential: {@link #receiveMatching} subscribes and
     * cancels repeatedly while draining, which would delete an auto-delete queue mid-drain.
     */
    private String bindCaptureQueue(UUID id) {
        var name = "test.sweep.capture." + id;
        amqpAdmin.declareQueue(new Queue(name, false, false, false));
        amqpAdmin.declareBinding(new Binding(name, Binding.DestinationType.QUEUE,
                DECISION_EXCHANGE, DECISION_ROUTING_KEY, null));
        declaredQueues.add(name);
        return name;
    }

    /** Raw body kept alongside the typed event so tests can assert on the actual wire bytes. */
    private record ReceivedDecision(EnrollmentDecisionEvent event, String json) {}

    private ReceivedDecision awaitDecisionFor(String queue, UUID decisionId) {
        var received = receiveMatching(queue, decisionId, Instant.now().plusSeconds(10));
        if (received == null) {
            throw new AssertionError("No EnrollmentDecisionEvent for decisionId=" + decisionId + " within timeout");
        }
        return received;
    }

    /**
     * Drains the capture queue until an event with {@code decisionId} appears or the deadline
     * passes. Other tests' decisions land on the same exchange; matching isolates this test.
     * (The event deliberately carries no enrollmentId — decisionId is the only published correlation.)
     */
    private ReceivedDecision receiveMatching(String queue, UUID decisionId, Instant deadline) {
        while (Instant.now().isBefore(deadline)) {
            var message = rabbitTemplate.receive(queue, 200);
            if (message == null) {
                continue;
            }
            var json = new String(message.getBody(), StandardCharsets.UTF_8);
            var event = jsonMapper.readValue(json, EnrollmentDecisionEvent.class);
            if (event.decisionId().equals(decisionId)) {
                return new ReceivedDecision(event, json);
            }
        }
        return null;
    }
}
