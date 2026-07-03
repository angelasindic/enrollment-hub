package dev.sindic.enrollmenthub.decisionengine.service;

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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
 *   <li>If all applicable signals are settled, evaluate the decision and persist
 *       it (also via explicit {@code UPDATE}) — deciding only; no publish inside
 *       the locked transaction.</li>
 * </ol>
 *
 * <p>Emission is separated from deciding per ADR-17 (commit-then-publish): the decision —
 * including its frozen {@code decisionId} — is durable at commit, and {@link #finalizeDecision}
 * registers an after-commit hook that triggers the eager dispatch via {@link DecisionDispatcher}.
 * If the eager publish fails, the row stays in the outbox state ({@code decision_result NOT NULL,
 * dispatched_at NULL}) and the {@link EnrollmentSweepJob} dispatch phase re-publishes it — always
 * the same persisted decision, so downstream {@code decisionId} dedup holds across every retry.
 *
 * @see EnrollmentIntakeService synchronous intake counterpart
 */
@Service
@Slf4j
public class EnrollmentService {

    private final EnrollmentRepository repository;
    private final DecisionDispatcher dispatcher;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    EnrollmentService(EnrollmentRepository repository,
                      DecisionDispatcher dispatcher,
                      JsonMapper jsonMapper,
                      Clock clock) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
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
     * Shared terminal step (ADR-16 §finalize, ADR-17 decide half). Given a fully-settled signal
     * map for a row already locked {@code PESSIMISTIC_WRITE} by the caller, evaluate the decision
     * and persist it — decision result, frozen {@code decisionId}, {@code decidedAt} — via the
     * single-statement completion (guarded by {@code decision_result IS NULL}). No publish happens
     * here: the after-commit hook registered below triggers the eager dispatch once the decision
     * is durable, and the relay covers the case where that eager dispatch fails.
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
            // row under our lock (structurally impossible, but if it happens we must not dispatch).
            log.warn("completeWithDecision affected {} rows for enrollmentId={}; dispatch not registered",
                    rows, enrollmentId);
            return;
        }

        registerEagerDispatch(enrollmentId);
    }

    /**
     * Registers the eager half of the ADR-17 emission model: once the surrounding decide
     * transaction commits, {@link DecisionDispatcher#dispatchNow} publishes the just-persisted
     * decision. The hook swallows failures by design — the commit is already durable, a throw
     * from afterCommit would nack a correctly-processed message, and re-delivery is the
     * {@link EnrollmentSweepJob} dispatch phase's job, not the inbound message's.
     */
    private void registerEagerDispatch(UUID enrollmentId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    dispatcher.dispatchNow(enrollmentId);
                } catch (RuntimeException ex) {
                    log.warn("Eager decision dispatch failed for enrollmentId={}; "
                            + "the dispatch relay will re-publish", enrollmentId, ex);
                }
            }
        });
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
