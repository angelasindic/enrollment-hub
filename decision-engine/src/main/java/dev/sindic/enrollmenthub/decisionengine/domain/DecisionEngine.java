package dev.sindic.enrollmenthub.decisionengine.domain;

import java.util.Map;
import java.util.UUID;

/**
 * Aggregates settled signals from a completed {@link EnrollmentProcess} into a
 * single {@link DecisionResult}.
 *
 * <h2>Aggregation algorithm</h2>
 * The algorithm iterates over the process's signal map and dispatches on each
 * signal's {@link GateClassification}, accumulating two boolean flags:
 * <ul>
 *   <li>{@code rejected} — set when a {@link GateClassification#BEST_EFFORT} or
 *       {@link GateClassification#REQUIRED} signal settles with
 *       {@link SignalOutcome#FAILED}.</li>
 *   <li>{@code reviewRequired} — set when a {@link GateClassification#SCORING_SIGNAL}
 *       settles with {@link RiskLevel#HIGH} or {@link RiskLevel#EXTREME}.</li>
 * </ul>
 * At the end of the loop the flags are resolved in priority order:
 * {@code REJECTED} &gt; {@code CONDITIONAL_APPROVED} &gt; {@code APPROVED}.
 *
 * <h2>Fail-open behaviour</h2>
 * Signals with {@link SignalProcessingState#FAILED} (timeout or crash) contribute
 * nothing to either accumulator. No explicit fail-open branch is needed — absent
 * contributions are handled by omission.
 *
 * <h2>Asymmetric aggregation guarantee</h2>
 * {@link GateClassification#SCORING_SIGNAL} signals cannot drive
 * {@link DecisionResult#REJECTED}. This is enforced by control flow: the
 * {@code SCORING_SIGNAL} branch only sets {@code reviewRequired}; the
 * {@code rejected} accumulator is physically unreachable from that branch.
 *
 * <h2>{@code REQUIRED} classification</h2>
 * No current {@link SignalConfig} uses {@code REQUIRED}. The classification is
 * reserved for future signals (e.g. sanctions screening, regulated KYC) that must
 * be fail-closed. The timeout escalation policy (ADR-010) ensures a {@code REQUIRED}
 * signal never reaches aggregation in {@link SignalProcessingState#FAILED} state —
 * escalation fires first, so aggregation always sees {@code SETTLED}. Outcome
 * authority is identical to {@code BEST_EFFORT}: an explicit {@link SignalOutcome#FAILED}
 * drives {@link DecisionResult#REJECTED}; {@code OK} and {@code NO_RESULT} do not.
 */
public final class DecisionEngine {

    private DecisionEngine() {}

    /**
     * Evaluates the given signal map and returns a decision.
     *
     * @throws IllegalStateException            if any signal is still
     *                                          {@link SignalProcessingState#PENDING}
     * @throws AggregationPreconditionException if a signal is still
     *                                          {@link SignalProcessingState#PENDING}
     *                                          when aggregation runs — indicates a bug
     *                                          in the completion predicate
     */
    public static EnrollmentDecisionResult evaluate(Map<SignalConfig, SignalState> signals, UUID requestId) {
        if (!SignalConfig.allSettled(signals)) {
            throw new IllegalStateException(
                    "Cannot evaluate incomplete process " + requestId);
        }
        return new EnrollmentDecisionResult(aggregate(signals));
    }


    /**
     * Package-private to allow direct testing of the
     * {@link AggregationPreconditionException} guard without bypassing
     * the {@link #evaluate} completion check via reflection.
     */
    static DecisionResult aggregate(Map<SignalConfig, SignalState> signals) {
        var rejected      = false;
        var reviewRequired = false;

        for (var entry : signals.entrySet()) {
            var config = entry.getKey();
            var state  = entry.getValue();

            if (state.processingState() == SignalProcessingState.PENDING) {
                throw new AggregationPreconditionException(
                        "Aggregation triggered with pending signal: " + config.name());
            }

            switch (config.classification()) {

                case BEST_EFFORT -> {
                    // Drives REJECTED only when the check explicitly fails.
                    // FAILED processingState (timeout/crash) and NO_RESULT both fail-open.
                    if (state.processingState() == SignalProcessingState.SETTLED
                            && state.outcome() == SignalOutcome.FAILED) {
                        rejected = true;
                    }
                }

                case SCORING_SIGNAL -> {
                    // Advisory — flags for review at HIGH and EXTREME; cannot drive REJECTED.
                    // FAILED processingState fails open with no routing consequence.
                    if (state.processingState() == SignalProcessingState.SETTLED
                            && (state.riskLevel() == RiskLevel.HIGH
                             || state.riskLevel() == RiskLevel.EXTREME)) {
                        reviewRequired = true;
                    }
                }

                case REQUIRED -> {
                    // ADR-010 escalation ensures a REQUIRED signal is always SETTLED by the
                    // time aggregation runs — FAILED processingState is resolved before the
                    // completion predicate fires. Like BEST_EFFORT, an explicit FAILED outcome
                    // drives REJECTED; OK and NO_RESULT fail-open.
                    if (state.processingState() == SignalProcessingState.SETTLED
                            && state.outcome() == SignalOutcome.FAILED) {
                        rejected = true;
                    }
                }
            }
        }

        if (rejected)        return DecisionResult.REJECTED;
        if (reviewRequired)  return DecisionResult.CONDITIONAL_APPROVED;
        return DecisionResult.APPROVED;
    }
}
