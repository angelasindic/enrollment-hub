package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;

import java.time.Instant;
import java.util.Objects;

/**
 * The intake message, published and consumed by the decision-engine alone (ADR-13
 * §Ingress Inversion). It lives here rather than in the shared contracts module because no other
 * service is a party to it.
 *
 * <p>{@code createdAt} is the reason this envelope exists: it records when the REST layer accepted
 * the request, so the correlation row's timeout deadline is anchored to submission time rather than
 * to whenever the broker happens to deliver. A delayed delivery or a redelivery therefore cannot
 * silently extend an enrollment's timeout budget (ADR-15).
 */
public record EnrollmentEvent(Instant createdAt, EnrollmentData enrollmentData) {
    public EnrollmentEvent {
        Objects.requireNonNull(createdAt, "created at timestamp must not be null");
        Objects.requireNonNull(enrollmentData, "enrollment data must not be null");
    }

    /** The correlation id as a String — every caller needs that form (MDC, CorrelationData, the 202). */
    public String enrollmentId() {
        return enrollmentData.enrollmentId().toString();
    }
}
