package dev.sindic.enrollmenthub.contracts.events;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;

import java.util.Objects;
import java.util.UUID;

/**
 * Command dispatched by the decision-engine to fraud detection on the {@code enrollment.check.request}
 * exchange (routing key {@code fraud.check}). Carries the full enrollment data — fraud detection
 * correlates across the whole request (identity, payment type, shipping and billing addresses).
 */
public record FraudCheckRequest(
        EnrollmentData enrollmentData
) {
    public FraudCheckRequest {
        Objects.requireNonNull(enrollmentData, "enrollmentData must not be null");
    }

    public UUID enrollmentId() {
        return enrollmentData.enrollmentId();
    }
}
