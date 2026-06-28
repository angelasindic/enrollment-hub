package dev.sindic.enrollmenthub.decisionengine.api;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnrollmentRequestTest {

    private static EnrollmentRequest.AddressDto address(String country) {
        return new EnrollmentRequest.AddressDto(
                List.of("1 Main St"), "10115", "Berlin", "BE", country);
    }

    private static EnrollmentRequest.PersonDto person() {
        return new EnrollmentRequest.PersonDto("Ada", "Lovelace", "ada@example.com", "+49123");
    }

    @Test
    void nullStreetLinesBecomesEmpty() {
        EnrollmentRequest.AddressDto a = new EnrollmentRequest.AddressDto(
                null, "10115", "Berlin", "BE", "DE");
        assertThat(a.streetLines()).isEmpty();
    }
}
