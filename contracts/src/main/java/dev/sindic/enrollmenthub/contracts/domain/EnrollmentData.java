package dev.sindic.enrollmenthub.contracts.domain;

import java.util.Objects;
import java.util.UUID;

public record EnrollmentData(UUID enrollmentId, PaymentType paymentType, Person person, Address shippingAddress, Address billingAddress) {
    public EnrollmentData {
        Objects.requireNonNull(enrollmentId, "enrollment id must not be null");
        Objects.requireNonNull(paymentType, "payment type must not be null");
        Objects.requireNonNull(person, "person must not be null");
        Objects.requireNonNull(shippingAddress, "shipping address must not be null");
        Objects.requireNonNull(billingAddress, "billing address must not be null");
    }
}
