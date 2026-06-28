package dev.sindic.enrollmenthub.decisionengine.domain;

/**
 * Processing lifecycle and settled result of a single signal in the scatter-gather pipeline.
 *
 * <p>Stored as part of a {@code Map<SignalConfig, SignalState>} JSONB column on the
 * correlation record. The flat record serialises trivially to JSONB without
 * Jackson type discriminators.
 *
 * <p>Which result field is meaningful is determined by the signal's classification:
 * <ul>
 *   <li>Check-style signals ({@link GateClassification#BEST_EFFORT}/{@code REQUIRED})
 *       populate {@code outcome}; {@code riskLevel} is null.</li>
 *   <li>Score-style signals ({@link GateClassification#SCORING_SIGNAL})
 *       populate {@code riskLevel}; {@code outcome} is null.</li>
 * </ul>
 * Both fields are null when the signal settled without producing a result,
 * or when {@code processingState} is {@link SignalProcessingState#FAILED}.
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
