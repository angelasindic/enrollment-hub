package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.decisionengine.amqp.EnrollmentDecisionPublisher;
import dev.sindic.enrollmenthub.decisionengine.domain.DecisionEngine;
import dev.sindic.enrollmenthub.decisionengine.domain.GateClassification;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalOutcome;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalProcessingState;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentEntity;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Asynchronous convergence service for the scatter-gather pipeline.
 *
 * <p>Two entry points drive a correlation row to its decision, and both converge on the shared
 * {@link #finalizeDecision} terminal step so a timeout-completed and a result-completed enrollment
 * emit through one path (ADR-17): {@link #recordSignalResult} (a signal result arrived) and
 * {@link #processExpiredTimeouts} (the timeout poller, ADR-15).
 *
 * <p>{@link #recordSignalResult} implements the ADR-16 protocol:
 * <ol>
 *   <li>Acquire a pessimistic row lock via
 *       {@link EnrollmentRepository#findByEnrollmentIdForUpdate(UUID)}.</li>
 *   <li>Idempotency guard — discard if the signal is already settled.</li>
 *   <li>Compute the new signal map via the immutable domain transition
 *       {@code EnrollmentProcess.withSignalResult(...)}.</li>
 *   <li>Persist via explicit {@code UPDATE} (ADR-16 §Write path) — no dirty-tracking on
 *       the JSONB column.</li>
 *   <li>If all applicable signals are settled, evaluate the decision, persist
 *       it (also via explicit {@code UPDATE}), and publish
 *       {@code EnrollmentDecisionEvent} — all inside the same locked transaction.</li>
 * </ol>
 *
 * <p>Publish-inside-transaction is intentional per ADR-16: if the publish
 * throws, the transaction rolls back and the inbound AMQP message is retried,
 * which re-acquires the lock and observes the now-uncommitted state as
 * still-PENDING. Downstream consumers handle the at-least-once delivery
 * window via {@code decisionId} dedup (ADR-13 §Delivery & Concurrency Guarantees).
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

        // Apply the single-signal transition to a copy of the signal map.
        var updatedSignals = new EnumMap<>(entity.getSignals());
        updatedSignals.put(signal, newState);

        // If this transition completes the process, finalize in one UPDATE (signals + decision)
        // via the shared path — avoids the intermediate row state where all signals are settled
        // but the decision column is still NULL. Otherwise persist the settled signal only.
        if (SignalConfig.allSettled(updatedSignals)) {
            finalizeDecision(entity, updatedSignals);
            return;
        }

        int rows = repository.updateSignals(enrollmentId, jsonMapper.writeValueAsString(updatedSignals));
        if (rows != 1) {
            throw new IllegalStateException(
                    "updateSignals affected " + rows + " rows for enrollmentId=" + enrollmentId);
        }
    }

    /**
     * Timeout poller path (ADR-15). Claims a batch of expired-and-undecided rows under
     * {@code PESSIMISTIC_WRITE} + {@code SKIP LOCKED} via
     * {@link EnrollmentRepository#claimPendingTimeouts}, applies the per-classification timeout policy
     * to each row's still-PENDING signals ({@link #applyTimeoutPolicy}), and finalizes it through the
     * same {@link #finalizeDecision} path the result handler uses. Pollers on other instances claim disjoint batches; a row held under a handler's WAIT
     * lock is skipped this pass and picked up next.
     *
     * @param now       cutoff — rows with {@code timeout_at <= now} are eligible
     * @param batchSize per-transaction claim size; small batches bound lock duration (ADR-15 §Lock variant)
     * @return number of rows finalized in this batch; the caller drains while this equals {@code batchSize}
     */
    @Transactional
    public int processExpiredTimeouts(Instant now, int batchSize) {
        var expired = repository.claimPendingTimeouts(now, PageRequest.ofSize(batchSize));
        for (var entity : expired) {
            finalizeDecision(entity, applyTimeoutPolicy(entity.getSignals()));
        }
        if (!expired.isEmpty()) {
            log.info("Timeout poller finalized {} expired enrollment(s)", expired.size());
        }
        return expired.size();
    }

    /**
     * Shared terminal step (ADR-16 §finalize). Given a fully-settled signal map for a row already
     * locked {@code PESSIMISTIC_WRITE} by the caller, evaluate the decision, persist it via the
     * single-statement completion (guarded by {@code decision_result IS NULL}), and publish the
     * {@code EnrollmentDecisionEvent}. The publish runs inside the caller's transaction: a publish
     * failure rolls the decision back and the work is retried (result redelivery or the next poll).
     *
     * <p>Precondition: the caller holds the row lock and every signal in {@code settledSignals}
     * has reached a terminal state.
     */
    private void finalizeDecision(EnrollmentEntity entity, Map<SignalConfig, SignalState> settledSignals) {
        var enrollmentId = entity.getEnrollmentId();
        var decision = DecisionEngine.evaluate(settledSignals, enrollmentId);
        var decisionId = UUID.randomUUID();
        var decidedAt = clock.instant();

        int rows = repository.completeWithDecision(enrollmentId,
                jsonMapper.writeValueAsString(settledSignals),
                decision.decision().name(), decisionId, decidedAt);
        if (rows != 1) {
            // The decision_result IS NULL guard rejected — another path already completed this
            // row under our lock (structurally impossible, but if it happens we must not double-publish).
            log.warn("completeWithDecision affected {} rows for enrollmentId={}; decision not published",
                    rows, enrollmentId);
            return;
        }

        publisher.publish(decisionEventMapper.buildDecisionEvent(
                entity, settledSignals, decision, decisionId, decidedAt));
    }

    /**
     * Timeout transition (ADR-15), applied per signal by {@link GateClassification}; already-terminal
     * signals are unchanged. Applied on the entity's signal map to match {@link #recordSignalResult}'s
     * direct-map style.
     * <ul>
     *   <li>{@code BEST_EFFORT} / {@code SCORING_SIGNAL} — <b>fail open</b>: the still-PENDING signal
     *       becomes {@link SignalState#failed()} (FAILED processing state), contributing nothing to
     *       aggregation. Degraded availability of a non-critical check does not block enrollment.</li>
     *   <li>{@code REQUIRED} — <b>fail closed</b>: the still-PENDING signal settles with
     *       {@link SignalOutcome#FAILED}, which drives {@link DecisionResult#REJECTED} in
     *       {@link DecisionEngine}. An unverifiable required check rejects rather than approves.</li>
     * </ul>
     */
    private static Map<SignalConfig, SignalState> applyTimeoutPolicy(Map<SignalConfig, SignalState> signals) {
        var timedOut = new EnumMap<>(signals);
        timedOut.replaceAll((signal, state) -> {
            if (state.processingState() != SignalProcessingState.PENDING) {
                return state;
            }
            return signal.classification() == GateClassification.REQUIRED
                    ? SignalState.settled(SignalOutcome.FAILED)
                    : SignalState.failed();
        });
        return timedOut;
    }
}
