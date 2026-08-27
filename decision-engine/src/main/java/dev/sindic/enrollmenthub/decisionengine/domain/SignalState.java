package dev.sindic.enrollmenthub.decisionengine.domain;

/**
 * Processing lifecycle and settled result of one signal (ADR-14).
 *
 * <p>Two flat result fields rather than a sealed hierarchy, so the enclosing
 * {@code Map<SignalConfig, SignalState>} serialises to JSONB without type discriminators.
 * Which field is meaningful follows the signal's {@link GateClassification}: check-style
 * signals populate {@code outcome}, score-style signals populate {@code riskLevel}. Both are
 * null when the signal settled without a result, or when it FAILED.
 *
 * @param processingState workflow lifecycle — did the signal run?
 * @param outcome         non-null when SETTLED for check-style signals
 * @param riskLevel       non-null when SETTLED for score-style signals
 * @param reason          optional failure or no-result context
 */
public record SignalState(
        SignalProcessingState processingState,
        SignalOutcome outcome,
        RiskLevel riskLevel,
        String reason
) {

    public boolean hasSettled() {
        return processingState != SignalProcessingState.PENDING;
    }

    public static SignalState pending() {
        return new SignalState(SignalProcessingState.PENDING, null, null, null);
    }

    /** SETTLED with a check-style outcome (BEST_EFFORT / REQUIRED signals). */
    public static SignalState settled(SignalOutcome outcome) {
        return new SignalState(SignalProcessingState.SETTLED, outcome, null, null);
    }

    /** SETTLED with a score-style risk level (SCORING_SIGNAL signals). */
    public static SignalState settled(RiskLevel riskLevel) {
        return new SignalState(SignalProcessingState.SETTLED, null, riskLevel, null);
    }

    /**
     * SETTLED but the signal could not produce a result (e.g. geocoding failure).
     * Fail-open — does not contribute to the aggregation accumulators.
     */
    public static SignalState settledWithoutResult(String reason) {
        return new SignalState(SignalProcessingState.SETTLED, null, null, reason);
    }

    /** Service did not respond (timeout or crash). Fail-open for BEST_EFFORT and SCORING_SIGNAL. */
    public static SignalState failed() {
        return new SignalState(SignalProcessingState.FAILED, null, null, null);
    }
}
