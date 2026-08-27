package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import dev.sindic.enrollmenthub.contracts.domain.Person;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Wire round-trip for the intake envelope. Lives here rather than in the contracts module because
 * {@link EnrollmentEvent} is internal to the decision-engine.
 */
class EnrollmentEventSerializationTest {

    private static final Instant FIXED_CREATED_AT = Instant.parse("2026-05-23T10:00:00Z");

    private final JsonMapper mapper = JsonMapper.builder()
            .findAndAddModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static EnrollmentData enrollmentData(PaymentType paymentType) {
        var address = new Address(List.of("123 Main St"), "10115", "Berlin", "BE", "DE");
        var person = new Person("Jane", "Doe", "jane@example.com", "+491234567890");
        return new EnrollmentData(UUID.randomUUID(), paymentType, person, address, address);
    }

    @Test
    void enrollmentEvent_roundTrip() throws Exception {
        var original = new EnrollmentEvent(FIXED_CREATED_AT, enrollmentData(PaymentType.CREDIT_CARD));
        var json = mapper.writeValueAsString(original);
        assertEquals(original, mapper.readValue(json, EnrollmentEvent.class));
    }

    @Test
    void enrollmentEvent_invoiceRoute_roundTrip() throws Exception {
        var original = new EnrollmentEvent(FIXED_CREATED_AT, enrollmentData(PaymentType.INVOICE));
        var json = mapper.writeValueAsString(original);
        assertEquals(original, mapper.readValue(json, EnrollmentEvent.class));
    }

    @Test
    void enrollmentEvent_nullCreatedAt_throws() {
        assertThrows(NullPointerException.class,
                () -> new EnrollmentEvent(null, enrollmentData(PaymentType.INVOICE)));
    }

    @Test
    void enrollmentEvent_nullEnrollmentData_throws() {
        assertThrows(NullPointerException.class,
                () -> new EnrollmentEvent(FIXED_CREATED_AT, null));
    }

    @Test
    void enrollmentEvent_unknownFieldsIgnored() {
        String json = """
                {"createdAt":"2026-05-23T10:00:00Z",
                 "enrollmentData":{"enrollmentId":"%s",
                                "paymentType":"CREDIT_CARD",
                                "person":{"emailAddress":"test@example.com"},
                                "shippingAddress":{"countryCode":"DE"},
                                "billingAddress":{"countryCode":"DE"}},
                 "unknownFutureField":"ignored"}
                """.formatted(UUID.randomUUID());
        assertDoesNotThrow(() -> mapper.readValue(json, EnrollmentEvent.class));
    }
}
