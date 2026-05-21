package dev.sindic.enrollmenthub.decisionengine.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Response returned synchronously from {@code POST /enrollments}.
 * <p>
 * Enrollment is asynchronous: the decision-engine persists the correlation
 * row, publishes {@code EnrollmentAccepted}, and returns immediately with
 * HTTP {@code 202 Accepted}. The final outcome arrives later as an
 * {@code EnrollmentDecisionEvent}; clients correlate via {@link #requestId()}.
 * <p>
 * The response intentionally carries only what the client needs to (a) correlate
 * follow-up queries or webhooks and (b) confirm the request was accepted for
 * processing. It deliberately does NOT expose internal processing phases —
 * those are decision-engine implementation detail.
 *
 * @param requestId server-minted correlation id; the same value used as
 *                  {@code requestId} on every downstream event for this request
 * @param status    always {@link Status#ACCEPTED} on a {@code 2xx} response —
 *                  present as a field so the contract stays forward-compatible
 *                  if a synchronous rejection path is ever introduced
 */
public record EnrollmentResponse(UUID requestId, Status status) {

    public EnrollmentResponse {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    /**
     * Synchronous acknowledgement status. Scoped to what the HTTP layer can
     * assert at accept-time; terminal outcomes (approved / rejected / failed)
     * are delivered out-of-band via {@code EnrollmentDecisionEvent}.
     */
    public enum Status {
        /**
         * Request was validated, persisted, and {@code EnrollmentAccepted} was
         * published. Processing continues asynchronously.
         */
        ACCEPTED
    }
}
