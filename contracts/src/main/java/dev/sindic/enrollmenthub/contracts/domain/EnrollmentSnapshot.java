package dev.sindic.enrollmenthub.contracts.domain;

import java.util.Objects;

/**
 * Enrollment data as submitted, with the correlation id withheld — it is the producer's internal
 * key and is not part of this contract. Consumers identify a decision by its {@code decisionId}.
 *
 * @see EnrollmentData the id-carrying form
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
