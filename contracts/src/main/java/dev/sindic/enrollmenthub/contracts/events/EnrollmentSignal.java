package dev.sindic.enrollmenthub.contracts.events;

import java.util.Objects;

/**
 * Settled result for one signal inside {@link EnrollmentDecisionEvent}.
 *
 * <p>One result field is set when the signal produced a result — {@code outcome} for check-style
 * signals, {@code riskLevel} for score-style. When it produced none, {@code reason} says why and
 * {@code outcome} separates the two ways that happens: null if it ran and could not
 * ({@link #noResult}), {@link SignalOutcome#NOT_EXECUTED} if it never answered
 * ({@link #notExecuted}). That is why a score-style signal can carry an outcome (ADR-14).
 *
 * <p>Prefer the factories: the canonical constructor is public for Jackson and admits combinations
 * they cannot produce.
 */
public record EnrollmentSignal(
        SignalOutcome outcome,
        RiskLevel riskLevel,
        String reason
) {
    public EnrollmentSignal {
        if (outcome != null && riskLevel != null) {
            throw new IllegalArgumentException(
                    "a signal reports one result, not both: outcome=" + outcome + " riskLevel=" + riskLevel);
        }
        if (outcome == SignalOutcome.NOT_EXECUTED && !Reason.isGiven(reason)) {
            throw new IllegalArgumentException(
                    "NOT_EXECUTED must carry a reason — it is the only record of why the signal is missing");
        }
    }

    /** A check-style signal reached a verdict. */
    public static EnrollmentSignal checked(SignalOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (outcome == SignalOutcome.NOT_EXECUTED) {
            throw new IllegalArgumentException("NOT_EXECUTED is the absence of a verdict — use notExecuted(reason)");
        }
        return new EnrollmentSignal(outcome, null, null);
    }

    /** A score-style signal produced a risk tier. */
    public static EnrollmentSignal scored(RiskLevel riskLevel) {
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        return new EnrollmentSignal(null, riskLevel, null);
    }

    /** The signal ran and could not produce a result. */
    public static EnrollmentSignal noResult(String reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        return new EnrollmentSignal(null, null, reason);
    }

    /** No result arrived before the decision deadline. */
    public static EnrollmentSignal notExecuted(String reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        return new EnrollmentSignal(SignalOutcome.NOT_EXECUTED, null, reason);
    }
}
