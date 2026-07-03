package dev.sindic.enrollmenthub.contracts.domain;

import java.util.Objects;

/**
 * Enrollment data as submitted at intake, without the internal correlation id.
 *
 * <p>This is the payload embedded in {@code EnrollmentDecisionEvent}. The decision-engine's
 * {@code enrollmentId} is its correlation-record primary key and is deliberately not published
 * downstream (ADR-17); consumers identify and deduplicate a decision by its {@code decisionId}.
 * {@link EnrollmentData} remains the id-carrying payload for the intake and check-request hops,
 * where the correlation id is required.
 */
public record EnrollmentSnapshot(
        PaymentType paymentType,
        Person person,
        Address shippingAddress,
        Address billingAddress
) {
    public EnrollmentSnapshot {
        Objects.requireNonNull(paymentType, "payment type must not be null");
        Objects.requireNonNull(person, "person must not be null");
        Objects.requireNonNull(shippingAddress, "shipping address must not be null");
        Objects.requireNonNull(billingAddress, "billing address must not be null");
    }
}
