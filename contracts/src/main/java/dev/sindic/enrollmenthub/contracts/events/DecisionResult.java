package dev.sindic.enrollmenthub.contracts.events;

/**
 * Enrollment outcome produced by the decision engine and published in
 * {@link EnrollmentDecisionEvent}.
 */
public enum DecisionResult {
    APPROVED,
    REJECTED,
    CONDITIONAL_APPROVED
}
