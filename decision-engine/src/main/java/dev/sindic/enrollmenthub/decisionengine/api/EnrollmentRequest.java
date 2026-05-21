package dev.sindic.enrollmenthub.decisionengine.api;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.contracts.domain.Person;
import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request payload for {@code POST /enrollment/public/v1/enrollments}.
 * <p>
 * Intentionally mirrors {@link EnrollmentData} one-to-one so the mapping to the
 * {@code EnrollmentAccepted} event is a trivial constructor call — see
 * {@link #toEnrollmentData()}. The request never carries a {@code requestId};
 * the decision-engine mints that when accepting the request.
 */
public record EnrollmentRequest(
        @NotNull PaymentType paymentType,
        @NotNull @Valid PersonDto person,
        @NotNull @Valid AddressDto shippingAddress,
        @NotNull @Valid AddressDto billingAddress
) {

    public EnrollmentData toEnrollmentData() {
        return new EnrollmentData(
                paymentType,
                person.toPerson(),
                shippingAddress.toAddress(),
                billingAddress.toAddress()
        );
    }

    public record PersonDto(
            String firstName,
            String lastName,
            @NotBlank String emailAddress,
            String phoneNumber
    ) {
        public Person toPerson() {
            return new Person(firstName, lastName, emailAddress, phoneNumber);
        }
    }

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

        public Address toAddress() {
            return new Address(streetLines, postalCode, city, subregion, countryCode);
        }
    }
}
