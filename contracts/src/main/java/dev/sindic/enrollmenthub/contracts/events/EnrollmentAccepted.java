package dev.sindic.enrollmenthub.contracts.events;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;

import java.util.Objects;
import java.util.UUID;

/**
 * Published by the decision-engine to the {@code enrollment.events} topic exchange
 * after prechecks pass, triggering the assurance pipeline.
 * Routing key encodes payment type:
 * <ul>
 *   <li>CREDIT_CARD → {@code enrollment.created.credit_card}</li>
 *   <li>INVOICE     → {@code enrollment.created.invoice}</li>
 * </ul>
 * Consumed by geo-scoring (credit_card route), identity (invoice route),
 * and fraud detection (both routes via {@code enrollment.created.*}).
 */
public record EnrollmentAccepted(
        UUID requestId,
        EnrollmentData enrollmentData

) {
    public EnrollmentAccepted {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(enrollmentData, "enrollment data must not be null");
    }
}
