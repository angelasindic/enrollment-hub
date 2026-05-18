package dev.sindic.enrollmenthub.contracts.domain;

import java.util.Objects;

public record Person(String firstName, String lastName, String emailAddress, String phoneNumber) {
    public Person {
        Objects.requireNonNull(emailAddress, "email address must not be null");
    }
}
