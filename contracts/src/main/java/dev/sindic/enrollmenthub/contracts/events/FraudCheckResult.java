package dev.sindic.enrollmenthub.contracts.events;

import java.util.Objects;
import java.util.UUID;

/** Result of the fraud check. Owned by fraud-detection. Produced on every payment route. */
public record FraudCheckResult(
        UUID enrollmentId,
        SignalOutcome outcome
) {
    public FraudCheckResult {
        Objects.requireNonNull(enrollmentId, "enrollmentId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}
