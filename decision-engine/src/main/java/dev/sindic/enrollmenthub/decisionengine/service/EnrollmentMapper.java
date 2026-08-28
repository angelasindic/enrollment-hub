package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import dev.sindic.enrollmenthub.contracts.domain.Person;
import dev.sindic.enrollmenthub.decisionengine.domain.EnrollmentCommand;

/**
 * Domain → contracts translation for the intake publish path, plus the one enum bridge the
 * consume path needs.
 *
 * <p>There is no inbound aggregate mapper. The intake message is the decision-engine's own
 * (ADR-13 §Ingress Inversion): it publishes {@code EnrollmentEvent} and consumes it again, so the
 * payload arrives in the vocabulary it left in. Rebuilding {@link EnrollmentCommand} from it would
 * protect against nothing, because no domain logic runs on it — the consume path reads only
 * {@code paymentType}, and only to pick the applicable signals. The contracts boundary that does
 * need a translation is the signal results, which become {@code SignalState} (ADR-06, ADR-14).
 */
public final class EnrollmentMapper {

    private EnrollmentMapper() {}

    /** Domain command → contracts payload, for the intake publish path. */
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
     * The only contracts → domain crossing on the consume path, and the reason the domain keeps its
     * own {@code PaymentType}: {@code SignalConfig} keys every signal's applicable routes on it, so
     * the enum is the routing table, not a label. Sharing the contract enum would let a producer's
     * schema change reach route selection directly (ADR-06).
     */
    public static dev.sindic.enrollmenthub.decisionengine.domain.PaymentType toDomainPaymentType(
            PaymentType paymentType) {
        return dev.sindic.enrollmenthub.decisionengine.domain.PaymentType.valueOf(paymentType.name());
    }

    private static Address toAddress(dev.sindic.enrollmenthub.decisionengine.domain.Address a) {
        return new Address(a.streetLines(), a.postalCode(), a.city(), a.subregion(), a.countryCode());
    }
}
