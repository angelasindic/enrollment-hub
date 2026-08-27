package dev.sindic.enrollmenthub.decisionengine.domain;

import java.util.Objects;

/**
 * Result of evaluating a fully-settled signal map through the {@link DecisionEngine}.
 */
public record EnrollmentDecisionResult(DecisionResult decision) {

    public EnrollmentDecisionResult {
        Objects.requireNonNull(decision, "decision must not be null");
    }
}
