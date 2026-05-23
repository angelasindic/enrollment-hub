package dev.sindic.enrollmenthub.contracts.events;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;

import java.time.Instant;
import java.util.Objects;

/**
 * Published by the decision-engine, wraps enrollment data
 */
public record EnrollmentEvent(Instant createdAt, EnrollmentData enrollmentData) {
    public EnrollmentEvent {
        Objects.requireNonNull(createdAt, "created at timestamp must not be null");
        Objects.requireNonNull(enrollmentData, "enrollment data must not be null");
    }

    public String enrollmentId() {
        return enrollmentData.enrollmentId().toString();
    }
}
