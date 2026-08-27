package dev.sindic.enrollmenthub.contracts.events;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;

import java.util.Objects;
import java.util.UUID;

/**
 * Command requesting a fraud check. Carries the full enrollment data, since fraud signals correlate
 * across identity, payment type and both addresses.
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
