package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import dev.sindic.enrollmenthub.contracts.domain.Person;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentEvent;
import dev.sindic.enrollmenthub.decisionengine.domain.EnrollmentCommand;

/**
 * Two-way mapper between the {@code contracts} event payload and the
 * decision-engine {@link EnrollmentCommand} domain record. Used on both ends
 * of the intake hop:
 * <ul>
 *   <li>{@link #toData(EnrollmentCommand)} — outbound, for the publish path.</li>
 *   <li>{@link #toCommand(EnrollmentEvent)} — inbound, for the intake listener.</li>
 * </ul>
 */
public final class EnrollmentMapper {

    private EnrollmentMapper() {}

    /** Domain command → contracts payload, for outbound publishes. */
    public static EnrollmentData toData(EnrollmentCommand command) {
        return new EnrollmentData(
                command.enrollmentId(),
                PaymentType.valueOf(command.paymentType().name()),
                new Person(
                        command.person().firstName(),
                        command.person().lastName(),
                        command.person().emailAddress(),
                        command.person().phoneNumber()),
                toAddress(command.shippingAddress()),
                toAddress(command.billingAddress()));
    }

    /**
     * Inbound contracts event → domain command, for the intake listener.
     * The {@code createdAt} on {@link EnrollmentEvent} is carried separately
     * by the listener (not part of {@code EnrollmentCommand}).
     */
    public static EnrollmentCommand toCommand(EnrollmentEvent event) {
        EnrollmentData data = event.enrollmentData();
        return new EnrollmentCommand(
                data.enrollmentId(),
                dev.sindic.enrollmenthub.decisionengine.domain.PaymentType.valueOf(data.paymentType().name()),
                toDomainPerson(data.person()),
                toDomainAddress(data.shippingAddress()),
                toDomainAddress(data.billingAddress()));
    }

    private static Address toAddress(dev.sindic.enrollmenthub.decisionengine.domain.Address a) {
        return new Address(a.streetLines(), a.postalCode(), a.city(), a.subregion(), a.countryCode());
    }

    private static dev.sindic.enrollmenthub.decisionengine.domain.Person toDomainPerson(Person p) {
        return new dev.sindic.enrollmenthub.decisionengine.domain.Person(
                p.firstName(), p.lastName(), p.emailAddress(), p.phoneNumber());
    }

    private static dev.sindic.enrollmenthub.decisionengine.domain.Address toDomainAddress(Address a) {
        return new dev.sindic.enrollmenthub.decisionengine.domain.Address(
                a.streetLines(), a.postalCode(), a.city(), a.subregion(), a.countryCode());
    }
}
