package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentEvent;
import dev.sindic.enrollmenthub.decisionengine.domain.Address;
import dev.sindic.enrollmenthub.decisionengine.domain.EnrollmentCommand;
import dev.sindic.enrollmenthub.decisionengine.domain.PaymentType;
import dev.sindic.enrollmenthub.decisionengine.domain.Person;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static dev.sindic.enrollmenthub.decisionengine.service.EnrollmentMapper.toCommand;
import static dev.sindic.enrollmenthub.decisionengine.service.EnrollmentMapper.toData;
import static org.assertj.core.api.Assertions.assertThat;

class EnrollmentMapperTest {

    private static final UUID ENROLLMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-05-23T10:00:00Z");

    private static final EnrollmentCommand CREDIT_CARD_COMMAND = new EnrollmentCommand(
            ENROLLMENT_ID,
            PaymentType.CREDIT_CARD,
            new Person("Ada", "Lovelace", "ada@example.com", "+49123"),
            new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"),
            new Address(List.of("2 Billing Ave"), "10116", "Hamburg", "HH", "DE"));

    @Nested
    class ToData {

        @Test
        void enrollmentIdIsPreserved() {
            var data = toData(CREDIT_CARD_COMMAND);

            assertThat(data.enrollmentId()).isEqualTo(ENROLLMENT_ID);
        }

        @Test
        void creditCardPaymentTypeIsMapped() {
            var data = toData(CREDIT_CARD_COMMAND);

            assertThat(data.paymentType().name()).isEqualTo("CREDIT_CARD");
        }

        @Test
        void invoicePaymentTypeIsMapped() {
            var command = new EnrollmentCommand(
                    ENROLLMENT_ID,
                    PaymentType.INVOICE,
                    CREDIT_CARD_COMMAND.person(),
                    CREDIT_CARD_COMMAND.shippingAddress(),
                    CREDIT_CARD_COMMAND.billingAddress());

            var data = toData(command);

            assertThat(data.paymentType().name()).isEqualTo("INVOICE");
        }

        @Test
        void personFieldsAreMapped() {
            var person = toData(CREDIT_CARD_COMMAND).person();

            assertThat(person.firstName()).isEqualTo("Ada");
            assertThat(person.lastName()).isEqualTo("Lovelace");
            assertThat(person.emailAddress()).isEqualTo("ada@example.com");
            assertThat(person.phoneNumber()).isEqualTo("+49123");
        }

        @Test
        void shippingAddressFieldsAreMapped() {
            var address = toData(CREDIT_CARD_COMMAND).shippingAddress();

            assertThat(address.streetLines()).containsExactly("1 Main St");
            assertThat(address.postalCode()).isEqualTo("10115");
            assertThat(address.city()).isEqualTo("Berlin");
            assertThat(address.subregion()).isEqualTo("BE");
            assertThat(address.countryCode()).isEqualTo("DE");
        }

        @Test
        void shippingAndBillingAddressesAreMappedIndependently() {
            var data = toData(CREDIT_CARD_COMMAND);

            assertThat(data.shippingAddress().city()).isEqualTo("Berlin");
            assertThat(data.billingAddress().city()).isEqualTo("Hamburg");
        }
    }

    @Nested
    class ToCommand {

        @Test
        void enrollmentIdIsPreserved() {
            var command = toCommand(eventFrom(CREDIT_CARD_COMMAND));

            assertThat(command.enrollmentId()).isEqualTo(ENROLLMENT_ID);
        }

        @Test
        void creditCardPaymentTypeIsMapped() {
            var command = toCommand(eventFrom(CREDIT_CARD_COMMAND));

            assertThat(command.paymentType()).isEqualTo(PaymentType.CREDIT_CARD);
        }

        @Test
        void invoicePaymentTypeIsMapped() {
            var invoiceCommand = new EnrollmentCommand(
                    ENROLLMENT_ID,
                    PaymentType.INVOICE,
                    CREDIT_CARD_COMMAND.person(),
                    CREDIT_CARD_COMMAND.shippingAddress(),
                    CREDIT_CARD_COMMAND.billingAddress());

            var command = toCommand(eventFrom(invoiceCommand));

            assertThat(command.paymentType()).isEqualTo(PaymentType.INVOICE);
        }

        @Test
        void personFieldsAreMapped() {
            var person = toCommand(eventFrom(CREDIT_CARD_COMMAND)).person();

            assertThat(person.firstName()).isEqualTo("Ada");
            assertThat(person.lastName()).isEqualTo("Lovelace");
            assertThat(person.emailAddress()).isEqualTo("ada@example.com");
            assertThat(person.phoneNumber()).isEqualTo("+49123");
        }

        @Test
        void addressFieldsAreMapped() {
            var command = toCommand(eventFrom(CREDIT_CARD_COMMAND));

            assertThat(command.shippingAddress().city()).isEqualTo("Berlin");
            assertThat(command.shippingAddress().countryCode()).isEqualTo("DE");
            assertThat(command.billingAddress().city()).isEqualTo("Hamburg");
            assertThat(command.billingAddress().postalCode()).isEqualTo("10116");
        }

        @Test
        void roundTripPreservesCommand() {
            // toCommand(toData(x)) returns a command structurally equal to x.
            // Confirms the two directions stay symmetric — adding a field on
            // one side will fail this test if the other side isn't updated.
            var roundTripped = toCommand(eventFrom(CREDIT_CARD_COMMAND));

            assertThat(roundTripped).isEqualTo(CREDIT_CARD_COMMAND);
        }
    }

    private static EnrollmentEvent eventFrom(EnrollmentCommand command) {
        return new EnrollmentEvent(CREATED_AT, toData(command));
    }
}
