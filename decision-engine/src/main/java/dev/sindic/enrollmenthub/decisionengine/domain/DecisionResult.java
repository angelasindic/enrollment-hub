package dev.sindic.enrollmenthub.decisionengine.domain;

/**
 * Enrollment outcome produced by the {@link DecisionEngine}.
 */
public enum DecisionResult {
    APPROVED,
    REJECTED,
    CONDITIONAL_APPROVED
}
