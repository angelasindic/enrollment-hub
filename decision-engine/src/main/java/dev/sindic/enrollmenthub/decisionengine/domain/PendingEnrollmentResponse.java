package dev.sindic.enrollmenthub.decisionengine.domain;



import java.util.UUID;

public record PendingEnrollmentResponse(UUID requestId, Status status) {

    public static PendingEnrollmentResponse accepted(UUID requestId) {
        return new PendingEnrollmentResponse(requestId, Status.ACCEPTED);
    }

    public enum Status {
        /**
         * Request was validated, persisted, and {@code EnrollmentAccepted} was
         * published. Processing continues asynchronously.
         */
        ACCEPTED
    }
}
