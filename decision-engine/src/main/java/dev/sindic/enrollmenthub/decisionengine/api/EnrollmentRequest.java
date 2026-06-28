package dev.sindic.enrollmenthub.decisionengine.api;

import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request payload for {@code POST /enrollment/public/v1/enrollments}.
 */
public record EnrollmentRequest(
        @NotNull PaymentType paymentType,
        @NotNull @Valid PersonDto person,
        @NotNull @Valid AddressDto shippingAddress,
        @NotNull @Valid AddressDto billingAddress
) {

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
