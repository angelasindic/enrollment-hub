package dev.sindic.enrollmenthub.contracts.events;

/**
 * Settled result for a single signal, included in {@link EnrollmentDecisionEvent}.
 * Processing lifecycle ({@code SignalProcessingState}) is dropped — downstream
 * consumers only ever receive a decision after all signals have settled.
 *
 * <p>Exactly one of {@code outcome} or {@code riskLevel} is non-null per entry,
 * determined by the signal's classification:
 * <ul>
 *   <li>Check-style signals (BEST_EFFORT) populate {@code outcome}.</li>
 *   <li>Score-style signals (SCORING_SIGNAL) populate {@code riskLevel}.</li>
 * </ul>
 * Both may be null when the signal settled without producing a result (fail-open).
 */
public record EnrollmentSignal(
        SignalOutcome outcome,
        RiskLevel riskLevel,
        /** Non-null when the signal did not produce a result: timeout, crash, or geocoding failure. */
        String reason
) {}
