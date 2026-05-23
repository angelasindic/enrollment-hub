package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.decisionengine.amqp.EnrollmentDecisionPublisher;
import dev.sindic.enrollmenthub.decisionengine.domain.DecisionEngine;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalProcessingState;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.EnumMap;
import java.util.UUID;

/**
 * Asynchronous convergence service for the scatter-gather pipeline.
 *
 * <p>{@link #recordSignalResult} implements the ADR-015 protocol:
 * <ol>
 *   <li>Acquire a pessimistic row lock via
 *       {@link EnrollmentRepository#findByEnrollmentIdForUpdate(UUID)}.</li>
 *   <li>Idempotency guard — discard if the signal is already settled.</li>
 *   <li>Compute the new signal map via the immutable domain transition
 *       {@code EnrollmentProcess.withSignalResult(...)}.</li>
 *   <li>Persist via explicit {@code UPDATE} (ADR-015 §Write path) — no dirty-tracking on
 *       the JSONB column.</li>
 *   <li>If all applicable signals are settled, evaluate the decision, persist
 *       it (also via explicit {@code UPDATE}), and publish
 *       {@code EnrollmentDecisionEvent} — all inside the same locked transaction.</li>
 * </ol>
 *
 * <p>Publish-inside-transaction is intentional per ADR-015: if the publish
 * throws, the transaction rolls back and the inbound AMQP message is retried,
 * which re-acquires the lock and observes the now-uncommitted state as
 * still-PENDING. Downstream consumers handle the at-least-once delivery
 * window via {@code decisionId} dedup (ADR-003 §Delivery Guarantees).
 *
 * @see EnrollmentIntakeService synchronous intake counterpart
 */
@Service
@Slf4j
public class EnrollmentService {

    private final EnrollmentRepository repository;
    private final EnrollmentDecisionPublisher publisher;
    private final DecisionEventMapper decisionEventMapper;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    EnrollmentService(EnrollmentRepository repository,
                      EnrollmentDecisionPublisher publisher,
                      JsonMapper jsonMapper,
                      Clock clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.decisionEventMapper = new DecisionEventMapper(jsonMapper);
    }

    @Transactional
    public void recordSignalResult(UUID enrollmentId, SignalConfig signal, SignalState newState) {
        var entity = repository.findByEnrollmentIdForUpdate(enrollmentId)
                .orElseThrow(() -> new UnknownCorrelationException(enrollmentId));

        // Idempotency guard — duplicate delivery or late arrival after timeout.
        var currentSignal = entity.getSignals().get(signal);
        if (currentSignal == null || currentSignal.processingState() != SignalProcessingState.PENDING) {
            log.warn("{} already recorded — idempotent discard", signal);
            return;
        }

        // Compute the new signal map via the immutable domain transition.
        var updatedSignals = new EnumMap<>(entity.getSignals());
        updatedSignals.put(signal, newState);
        var signalsJson = jsonMapper.writeValueAsString(updatedSignals);

        // Branch on whether this transition completes the process so we can
        // collapse the signal-update and decision-record into a single UPDATE
        // on the completion path. Avoids the intermediate row state where all
        // signals are settled but the decision column is still NULL.
        if (!SignalConfig.allSettled(updatedSignals)) {
            int rows = repository.updateSignals(enrollmentId, signalsJson);
            if (rows != 1) {
                throw new IllegalStateException(
                        "updateSignals affected " + rows + " rows for enrollmentId=" + enrollmentId);
            }
            return;
        }

        var decision = DecisionEngine.evaluate(updatedSignals, enrollmentId);
        var decisionId = UUID.randomUUID();
        var decidedAt = clock.instant();

        int rows = repository.completeWithDecision(
                enrollmentId, signalsJson, decision.decision().name(), decisionId, decidedAt);
        if (rows != 1) {
            // The decision_result IS NULL guard rejected — another path already
            // completed under our lock (structurally impossible, but if it
            // happens we must not double-publish).
            log.warn("completeWithDecision affected {} rows for enrollmentId={}; decision not published",
                    rows, enrollmentId);
            return;
        }

        publisher.publish(decisionEventMapper.buildDecisionEvent(
                entity, updatedSignals, decision, decisionId, decidedAt));
    }
}
