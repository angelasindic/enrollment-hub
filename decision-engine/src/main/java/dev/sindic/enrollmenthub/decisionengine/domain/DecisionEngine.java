package dev.sindic.enrollmenthub.decisionengine.domain;

import java.util.Map;
import java.util.UUID;

/**
 * Aggregates a fully-settled signal map into a {@link DecisionResult} (ADR-14).
 *
 * <p>One pass over the map dispatching on {@link GateClassification}, accumulating two flags —
 * {@code rejected} (a {@code BEST_EFFORT} or {@code REQUIRED} signal settled
 * {@link SignalOutcome#FAILED}) and {@code reviewRequired} (a {@code SCORING_SIGNAL} settled
 * {@link RiskLevel#HIGH} or {@code EXTREME}) — resolved in priority order
 * {@code REJECTED} &gt; {@code CONDITIONAL_APPROVED} &gt; {@code APPROVED}.
 *
 * <p>Two properties hold by control flow rather than by assertion:
 * <ul>
 *   <li><b>A scoring signal cannot reject.</b> Its branch only ever sets {@code reviewRequired};
 *       {@code rejected} is unreachable from there.</li>
 *   <li><b>Fail-open is by omission.</b> A {@link SignalProcessingState#FAILED} signal (timeout or
 *       crash) matches no accumulator condition, so no explicit branch is needed.</li>
 * </ul>
 *
 * <p>No {@link SignalConfig} is {@code REQUIRED} today — the classification is reserved for
 * fail-closed checks such as sanctions screening. Its branch is still required: the ADR-15 timeout
 * policy settles a timed-out {@code REQUIRED} signal as {@code SETTLED + FAILED} rather than
 * failing it open, so it arrives here as an explicit rejection.
 */
public final class DecisionEngine {

    private DecisionEngine() {}

    /**
     * Evaluates the signal map and returns the decision.
     *
     * @throws AggregationPreconditionException (an {@link IllegalStateException}) if any signal is
     *         still {@link SignalProcessingState#PENDING} — the caller's completion predicate is
     *         broken, since only a fully-settled map may be evaluated
     */
    public static EnrollmentDecisionResult evaluate(Map<SignalConfig, SignalState> signals, UUID enrollmentId) {
        return new EnrollmentDecisionResult(aggregate(signals, enrollmentId));
    }

    private static DecisionResult aggregate(Map<SignalConfig, SignalState> signals, UUID enrollmentId) {
        var rejected      = false;
        var reviewRequired = false;

        for (var entry : signals.entrySet()) {
            var config = entry.getKey();
            var state  = entry.getValue();

            if (state.processingState() == SignalProcessingState.PENDING) {
                throw new AggregationPreconditionException(
                        "Cannot evaluate incomplete enrollment " + enrollmentId
                                + " — signal still pending: " + config.name());
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
                    // Fail-closed. applyTimeoutPolicy settles a timed-out REQUIRED signal with
                    // SETTLED + FAILED (never FAILED processingState), so a missing required check
                    // reaches this branch as an explicit FAILED outcome and drives REJECTED.
                    // OK and NO_RESULT fail-open, as for BEST_EFFORT.
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
