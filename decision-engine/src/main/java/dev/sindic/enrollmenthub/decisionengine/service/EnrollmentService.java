package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.decisionengine.domain.DecisionEngine;
import dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult;
import dev.sindic.enrollmenthub.decisionengine.domain.GateClassification;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.CheckOutcome;
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
 * Gather half of the scatter-gather pipeline — decides an enrollment once its applicable
 * signals have settled, and emits the decision.
 *
 * <p>An enrollment reaches its decision by one of two paths: {@link #recordSignalResult}, when
 * the last outstanding signal result arrives, or {@link #processExpiredTimeouts}, when the timeout
 * deadline passes first (ADR-15). Both converge on {@link #finalizeDecision} (ADR-17).
 *
 * <p>{@link #recordSignalResult} follows the ADR-16 protocol: lock the row, discard results for
 * signals absent from the map (not applicable to this route) or already past {@code PENDING}, then
 * persist a replaced signal map by explicit {@code UPDATE} — no dirty-tracking on the JSONB column.
 * When the result settles the last signal, that same statement also writes the decision, so the row
 * is never observable fully settled with a NULL decision.
 *
 * <p>Deciding and emitting are separate steps (ADR-17): the decision and its {@code decisionId}
 * are committed to the row before anything is published — a publish cannot be rolled back — which
 * makes the row an outbox. {@link #finalizeDecision} registers an after-commit hook that emits via
 * {@link DecisionDispatcher}; whatever it fails to emit stays in the outbox state
 * ({@code decision_result NOT NULL, dispatched_at NULL}) for the {@link EnrollmentSweepJob} relay.
 * Both publish the same stored {@code decisionId}, so a retry is a duplicate downstream dedups.
 *
 * @see EnrollmentIntakeService scatter half —2 intake and per-signal command dispatch
 */
@Service
@Slf4j
public class EnrollmentService {

    /** Recorded on every signal the timeout poller settles, so the row says why it is missing. */
    static final String TIMEOUT_REASON = "timeout";

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

    /**
     * Records one signal result on the locked correlation row, per the ADR-16 protocol above.
     * Duplicate and late results are discarded idempotently.
     *
     * @throws UnknownCorrelationException  if no row exists for {@code enrollmentId}
     * @throws SignalShapeMismatchException if the state is one the signal's classification cannot
     *         use — checked before the row is touched, since the row is not what is wrong
     */
    @Transactional
    public void recordSignalResult(UUID enrollmentId, SignalConfig signal, SignalState newState) {
        if (!signal.classification().admits(newState)) {
            throw new SignalShapeMismatchException(signal, newState);
        }

        var entity = repository.findByEnrollmentIdForUpdate(enrollmentId)
                .orElseThrow(() -> new UnknownCorrelationException(enrollmentId));

        // Idempotency guard — duplicate delivery or late arrival after timeout.
        var currentSignal = entity.getSignals().get(signal);
        if (!(currentSignal instanceof  SignalState.Pending)) {
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

        int rows = repository.updateSignals(enrollmentId, SignalMapJson.write(jsonMapper, updatedSignals));
        if (rows != 1) {
            throw new IllegalStateException(
                    "updateSignals affected " + rows + " rows for enrollmentId=" + enrollmentId);
        }
    }

    /**
     * Timeout poller path (ADR-15). Claims a batch of expired-and-undecided rows under
     * {@code PESSIMISTIC_WRITE} + {@code SKIP LOCKED} via
     * {@link EnrollmentRepository#claimPendingTimeouts}, applies {@link #applyTimeoutPolicy} to
     * each row's still-PENDING signals, and finalizes through {@link #finalizeDecision}. Pollers
     * on other instances claim disjoint batches; a row held under a handler's WAIT lock is picked
     * up next pass.
     *
     * @param now       cutoff — rows with {@code timeout_at <= now} are eligible
     * @param batchSize per-transaction claim size; small batches bound lock duration (ADR-15 §Lock variant)
     * @return number of rows claimed in this batch; the caller drains while this equals {@code batchSize}
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
     * Shared terminal step (ADR-16 §finalize, ADR-17 decide half). Evaluates the decision and
     * persists it — result, {@code decisionId}, {@code decidedAt} — in one statement guarded by
     * {@code decision_result IS NULL}. No publish here: the after-commit hook below dispatches
     * once the decision is durable, with the relay as backstop.
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
                SignalMapJson.write(jsonMapper, settledSignals),
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
     * Eager half of the ADR-17 emission model: once the decide transaction commits,
     * {@link DecisionDispatcher#dispatchNow} publishes the just-persisted decision. Failures are
     * swallowed by design — the commit is already durable, throwing from {@code afterCommit} would
     * nack a correctly-processed message, and re-publishing is the {@link EnrollmentSweepJob}
     * relay's job.
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
     * signals are unchanged.
     * <ul>
     *   <li>{@code BEST_EFFORT} / {@code SCORING_SIGNAL} — <b>fail open</b>: becomes
     *       {@link SignalState.NotExecuted}, contributing nothing to aggregation. Degraded
     *       availability of a non-critical check does not block enrollment. Distinct from
     *       {@link SignalState.NoResult}, which means the service ran and could not produce a
     *       value — a distinction ADR-14 requires the model to keep.</li>
     *   <li>{@code REQUIRED} — <b>fail closed</b>: settles as {@link SignalState.Checked} with
     *       {@link CheckOutcome#FAILED}, which drives {@link DecisionResult#REJECTED}. An
     *       unverifiable required check rejects.</li>
     * </ul>
     */
    static Map<SignalConfig, SignalState> applyTimeoutPolicy(Map<SignalConfig, SignalState> signals) {
        var result = new EnumMap<SignalConfig, SignalState>(SignalConfig.class);
        signals.forEach((signal, state) -> result.put(signal, onTimeout(signal, state)));
        return result;
    }

    private static SignalState onTimeout(SignalConfig signal, SignalState state) {

        return switch (state) {
            case SignalState.Pending _ -> switch (signal.classification()) {
                case REQUIRED ->  new SignalState.Checked(CheckOutcome.FAILED);
                case BEST_EFFORT, SCORING_SIGNAL -> new SignalState.NotExecuted("timeout");
            };
            case SignalState.Checked _,
                 SignalState.NotExecuted _,
                 SignalState.NoResult _,
                 SignalState.Scored _-> state;
        };
    }
}
