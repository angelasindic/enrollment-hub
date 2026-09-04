package dev.sindic.enrollmenthub.contracts.events;

import java.util.Objects;
import java.util.UUID;

/**
 * Result of the fraud check. Owned by fraud-detection. Produced on every payment route.
 * {@code noResultReason} is set when, and only when, the outcome is {@link CheckOutcome#NO_RESULT}
 * — a verdict needs no explanation, and a missing verdict is not worth much without one.
 */
public record FraudCheckResult(
        UUID enrollmentId,
        CheckOutcome outcome,
        /* Why no verdict was reached; set only for NO_RESULT. */
        String noResultReason
) {
    public FraudCheckResult {
        Objects.requireNonNull(enrollmentId, "enrollmentId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        boolean explained = Reason.isGiven(noResultReason);
        if ((outcome == CheckOutcome.NO_RESULT) != explained) {
            throw new IllegalArgumentException(
                    "noResultReason belongs to NO_RESULT and to nothing else, got outcome="
                            + outcome + " noResultReason=" + noResultReason);
        }
    }

    /** The check reached a verdict. */
    public static FraudCheckResult checked(UUID enrollmentId, CheckOutcome outcome) {
        return new FraudCheckResult(enrollmentId, outcome, null);
    }

    /** The check ran and could not reach a verdict. */
    public static FraudCheckResult noResult(UUID enrollmentId, String reason) {
        return new FraudCheckResult(enrollmentId, CheckOutcome.NO_RESULT, reason);
    }
}
