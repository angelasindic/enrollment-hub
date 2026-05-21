package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentAccepted;
import dev.sindic.enrollmenthub.decisionengine.domain.Address;
import dev.sindic.enrollmenthub.decisionengine.domain.EnrollmentCommand;
import dev.sindic.enrollmenthub.decisionengine.domain.PaymentType;
import dev.sindic.enrollmenthub.decisionengine.domain.Person;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static dev.sindic.enrollmenthub.decisionengine.service.EnrollmentAcceptedMapper.toData;
import static org.assertj.core.api.Assertions.assertThat;

class EnrollmentAcceptedMapperTest {

    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final EnrollmentCommand CREDIT_CARD_COMMAND = new EnrollmentCommand(
            PaymentType.CREDIT_CARD,
            new Person("Ada", "Lovelace", "ada@example.com", "+49123"),
            new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"),
            new Address(List.of("2 Billing Ave"), "10116", "Hamburg", "HH", "DE"));

    @Test
    void correlationIdIsPreserved() {
        EnrollmentAccepted event = toEvent(REQUEST_ID, CREDIT_CARD_COMMAND);

        assertThat(event.requestId()).isEqualTo(REQUEST_ID);
    }

    @Test
    void creditCardPaymentTypeIsMapped() {
        EnrollmentAccepted event = toEvent(REQUEST_ID, CREDIT_CARD_COMMAND);

        assertThat(event.enrollmentData().paymentType().name()).isEqualTo("CREDIT_CARD");
    }

    @Test
    void invoicePaymentTypeIsMapped() {
        var command = new EnrollmentCommand(
                PaymentType.INVOICE,
                CREDIT_CARD_COMMAND.person(),
                CREDIT_CARD_COMMAND.shippingAddress(),
                CREDIT_CARD_COMMAND.billingAddress());

        EnrollmentAccepted event = toEvent(REQUEST_ID, command);

        assertThat(event.enrollmentData().paymentType().name()).isEqualTo("INVOICE");
    }

    @Test
    void personFieldsAreMapped() {
        EnrollmentAccepted event = toEvent(REQUEST_ID, CREDIT_CARD_COMMAND);

        var person = event.enrollmentData().person();
        assertThat(person.firstName()).isEqualTo("Ada");
        assertThat(person.lastName()).isEqualTo("Lovelace");
        assertThat(person.emailAddress()).isEqualTo("ada@example.com");
        assertThat(person.phoneNumber()).isEqualTo("+49123");
    }

    @Test
    void shippingAddressFieldsAreMapped() {
        EnrollmentAccepted event = toEvent(REQUEST_ID, CREDIT_CARD_COMMAND);

        var address = event.enrollmentData().shippingAddress();
        assertThat(address.streetLines()).containsExactly("1 Main St");
        assertThat(address.postalCode()).isEqualTo("10115");
        assertThat(address.city()).isEqualTo("Berlin");
        assertThat(address.subregion()).isEqualTo("BE");
        assertThat(address.countryCode()).isEqualTo("DE");
    }

    @Test
    void billingAddressFieldsAreMapped() {
        EnrollmentAccepted event = toEvent(REQUEST_ID, CREDIT_CARD_COMMAND);

        var address = event.enrollmentData().billingAddress();
        assertThat(address.streetLines()).containsExactly("2 Billing Ave");
        assertThat(address.postalCode()).isEqualTo("10116");
        assertThat(address.city()).isEqualTo("Hamburg");
        assertThat(address.subregion()).isEqualTo("HH");
        assertThat(address.countryCode()).isEqualTo("DE");
    }

    @Test
    void shippingAndBillingAddressesAreMappedIndependently() {
        EnrollmentAccepted event = toEvent(REQUEST_ID, CREDIT_CARD_COMMAND);

        assertThat(event.enrollmentData().shippingAddress().city()).isEqualTo("Berlin");
        assertThat(event.enrollmentData().billingAddress().city()).isEqualTo("Hamburg");
    }

    private static EnrollmentAccepted toEvent(UUID requestId, EnrollmentCommand command) {
        return new EnrollmentAccepted(requestId, toData(command));
    }

}
