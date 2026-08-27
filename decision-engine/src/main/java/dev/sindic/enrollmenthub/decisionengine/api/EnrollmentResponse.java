package dev.sindic.enrollmenthub.decisionengine.api;

import java.util.Objects;

/**
 * Response returned synchronously from {@code POST /enrollments} with HTTP {@code 202 Accepted}.
 * The request is published to the intake channel and the outcome arrives later as an
 * {@code EnrollmentDecisionEvent}; clients correlate via {@link #enrollmentId()}.
 *
 * @param enrollmentId server-minted correlation id, carried on every downstream event for this
 *                     request
 */
public record EnrollmentResponse(String enrollmentId) {

    public EnrollmentResponse {
        Objects.requireNonNull(enrollmentId, "enrollmentId must not be null");
    }

}
