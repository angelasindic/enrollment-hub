package dev.sindic.enrollmenthub.contracts.events;

import java.util.Objects;
import java.util.UUID;

/**
 * Published by Internal Fraud Detection, consumed by the decision-engine.
 * Produced on both routes (CREDIT_CARD and INVOICE).
 */
public record FraudCheckResult(
        UUID requestId,
        SignalOutcome outcome
) {
    public FraudCheckResult {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}
