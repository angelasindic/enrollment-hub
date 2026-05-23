package dev.sindic.enrollmenthub.decisionengine.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Response returned synchronously from {@code POST /enrollments}.
 * <p>
 * Enrollment is asynchronous: the decision-engine persists the correlation
 * row, publishes {@code EnrollmentAccepted}, and returns immediately with
 * HTTP {@code 202 Accepted}. The final outcome arrives later as an
 * {@code EnrollmentDecisionEvent}; clients correlate via {@link #enrollmentId()}.
 * <p>
 *
 * @param enrollmentId server-minted correlation id; the same value used as
 *                  {@code requestId} on every downstream event for this request
 */
public record EnrollmentResponse(String enrollmentId) {

    public EnrollmentResponse {
        Objects.requireNonNull(enrollmentId, "requestId must not be null");
    }

}
