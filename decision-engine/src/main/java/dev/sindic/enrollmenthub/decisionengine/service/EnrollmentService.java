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
import java.util.UUID;

/**
 * Asynchronous convergence service for the scatter-gather pipeline.
 *
 * <p>{@link #recordSignalResult} implements the ADR-015 protocol:
 * <ol>
 *   <li>Acquire a pessimistic row lock via
 *       {@link EnrollmentRepository#findByRequestIdForUpdate(UUID)}.</li>
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
 * @see CreateEnrollmentService synchronous intake counterpart
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
    public void recordSignalResult(UUID requestId, SignalConfig signal, SignalState newState) {
        var entity = repository.findByRequestIdForUpdate(requestId)
                .orElseThrow(() -> new UnknownCorrelationException(requestId));

        // Idempotency guard — duplicate delivery or late arrival after timeout.
        var currentSignal = entity.getSignals().get(signal);
        if (currentSignal == null || currentSignal.processingState() != SignalProcessingState.PENDING) {
            log.warn("{} already recorded — idempotent discard", signal);
            return;
        }

        // Compute the new signal map via the immutable domain transition.
        var updated = entity.toDomainForDecision().withSignalResult(signal, newState);

        // Persist via explicit UPDATE (ADR-015 §Write path). The row-count assertion turns
        // a silent persistence drift into a loud failure.
        int signalRows = repository.updateSignals(requestId, jsonMapper.writeValueAsString(updated.signals()));
        if (signalRows != 1) {
            throw new IllegalStateException(
                    "updateSignals affected " + signalRows + " rows for requestId=" + requestId);
        }

        if (!updated.isComplete()) {
            return;
        }

        // Evaluate, persist decision fields via explicit UPDATE, publish.
        var decision = DecisionEngine.evaluate(updated);
        var decisionId = UUID.randomUUID();
        var decidedAt = clock.instant();

        int decisionRows = repository.recordDecision(requestId, decision.decision(), decisionId, decidedAt);
        if (decisionRows != 1) {
            // The recordDecision guard (decisionResult IS NULL) rejected the write.
            // Under the row lock we hold this is structurally impossible, but if it
            // happens we must not publish — another path has already published.
            log.warn("recordDecision affected {} rows for requestId={}; decision not published",
                    decisionRows, requestId);
            return;
        }

        publisher.publish(decisionEventMapper.buildDecisionEvent(
                entity, updated.signals(), decision, decisionId, decidedAt));
    }
}
