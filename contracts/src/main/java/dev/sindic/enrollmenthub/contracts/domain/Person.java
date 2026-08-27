package dev.sindic.enrollmenthub.contracts.domain;

import java.util.Objects;

/**
 * Applicant identity. Only {@code emailAddress} is guaranteed present; the remaining fields are
 * optional and may be null.
 */
public record Person(
        String firstName,
        String lastName,
        String emailAddress,
        String phoneNumber
) {
    public Person {
        Objects.requireNonNull(emailAddress, "email address must not be null");
    }
}
