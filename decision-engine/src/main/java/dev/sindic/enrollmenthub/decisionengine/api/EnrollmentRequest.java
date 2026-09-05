package dev.sindic.enrollmenthub.decisionengine.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request payload for {@code POST /enrollment/public/v1/enrollments}, and the REST channel's
 * contract: every type it names is its own (ADR-06 §One channel, one contract).
 *
 * <p>That includes {@link PaymentTypeDto}. Binding the {@code contracts} enum here would make the
 * published event part of the HTTP contract; binding the domain enum would let an internal rename
 * change the JSON this endpoint accepts. Neither is a dependency a public API should carry, so the
 * accepted values are declared here and {@code EnumCompatibilityTest} pins them to the domain enum
 * the controller converts into.
 */
public record EnrollmentRequest(
        @NotNull PaymentTypeDto paymentType,
        @NotNull @Valid PersonDto person,
        @NotNull @Valid AddressDto shippingAddress,
        @NotNull @Valid AddressDto billingAddress
) {

    /** The payment routes this endpoint accepts. */
    public enum PaymentTypeDto {
        CREDIT_CARD,
        INVOICE
    }

    public record PersonDto(
            String firstName,
            String lastName,
            @NotBlank String emailAddress,
            String phoneNumber
    ) { }

    public record AddressDto(
            List<String> streetLines,
            String postalCode,
            String city,
            String subregion,
            @NotBlank String countryCode
    ) {
        public AddressDto {
            streetLines = streetLines == null ? List.of() : List.copyOf(streetLines);
        }
    }
}
