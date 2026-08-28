package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.decisionengine.domain.Address;
import dev.sindic.enrollmenthub.decisionengine.domain.EnrollmentCommand;
import dev.sindic.enrollmenthub.decisionengine.domain.PaymentType;
import dev.sindic.enrollmenthub.decisionengine.domain.Person;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static dev.sindic.enrollmenthub.decisionengine.service.EnrollmentMapper.toData;
import static dev.sindic.enrollmenthub.decisionengine.service.EnrollmentMapper.toDomainPaymentType;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapper is one-directional: the intake message is the decision-engine's own, so the consume
 * path reads {@code EnrollmentData} as it arrived and never rebuilds {@link EnrollmentCommand}
 * (ADR-06). There is therefore no round trip to assert here. Field-order drift between the domain
 * and contracts records is pinned by {@code RecordCompatibilityTest} instead.
 */
class EnrollmentMapperTest {

    private static final UUID ENROLLMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
    class ToDomainPaymentType {

        @Test
        void everyContractValueHasADomainCounterpart() {
            // The only contracts → domain crossing left on the consume path. A value added to the
            // contract enum without a domain counterpart fails here rather than at dispatch time.
            for (var contractType : dev.sindic.enrollmenthub.contracts.domain.PaymentType.values()) {
                assertThat(toDomainPaymentType(contractType).name()).isEqualTo(contractType.name());
            }
        }

        @Test
        void creditCardMapsToTheDomainEnum() {
            assertThat(toDomainPaymentType(dev.sindic.enrollmenthub.contracts.domain.PaymentType.CREDIT_CARD))
                    .isEqualTo(PaymentType.CREDIT_CARD);
        }

        @Test
        void invoiceMapsToTheDomainEnum() {
            assertThat(toDomainPaymentType(dev.sindic.enrollmenthub.contracts.domain.PaymentType.INVOICE))
                    .isEqualTo(PaymentType.INVOICE);
        }
    }
}
