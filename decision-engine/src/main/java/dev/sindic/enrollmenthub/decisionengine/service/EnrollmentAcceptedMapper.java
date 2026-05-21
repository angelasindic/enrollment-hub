package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import dev.sindic.enrollmenthub.contracts.domain.Person;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentAccepted;
import dev.sindic.enrollmenthub.decisionengine.domain.EnrollmentCommand;

import java.util.UUID;

final class EnrollmentAcceptedMapper {

    private EnrollmentAcceptedMapper() {}

    static EnrollmentData toData(EnrollmentCommand command) {
        return new EnrollmentData(
                PaymentType.valueOf(command.paymentType().name()),
                new Person(
                        command.person().firstName(),
                        command.person().lastName(),
                        command.person().emailAddress(),
                        command.person().phoneNumber()),
                toAddress(command.shippingAddress()),
                toAddress(command.billingAddress()));
    }

    private static Address toAddress(dev.sindic.enrollmenthub.decisionengine.domain.Address a) {
        return new Address(a.streetLines(), a.postalCode(), a.city(), a.subregion(), a.countryCode());
    }
}
