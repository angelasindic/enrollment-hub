package dev.sindic.enrollmenthub.contracts.events;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import dev.sindic.enrollmenthub.contracts.domain.Person;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EventSerializationTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .findAndAddModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static Address address(String countryCode) {
        return new Address(List.of("123 Main St"), "10115", "Berlin", "BE", countryCode);
    }

    private static Person person() {
        return new Person("Jane", "Doe", "jane@example.com", "+491234567890");
    }

    private static EnrollmentData enrollmentData(PaymentType paymentType) {
        return new EnrollmentData(UUID.randomUUID(), paymentType, person(), address("DE"), address("DE"));
    }

    private static final Instant FIXED_CREATED_AT = Instant.parse("2026-05-23T10:00:00Z");

    // ── EnrollmentEvent ───────────────────────────────────────────────────────

    @Test
    void enrollmentEvent_roundTrip() throws Exception {
        var original = new EnrollmentEvent(FIXED_CREATED_AT, enrollmentData(PaymentType.CREDIT_CARD));
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, EnrollmentEvent.class);
        assertEquals(original, deserialized);
    }

    @Test
    void enrollmentEvent_invoiceRoute_roundTrip() throws Exception {
        var original = new EnrollmentEvent(FIXED_CREATED_AT, enrollmentData(PaymentType.INVOICE));
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, EnrollmentEvent.class);
        assertEquals(original, deserialized);
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
    void enrollmentEvent_unknownFieldsIgnored() throws Exception {
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

    // ── EnrollmentData ────────────────────────────────────────────────────────

    @Test
    void enrollmentData_roundTrip() throws Exception {
        var original = enrollmentData(PaymentType.INVOICE);
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, EnrollmentData.class);
        assertEquals(original, deserialized);
    }

    @Test
    void enrollmentData_nullPaymentType_throws() {
        assertThrows(NullPointerException.class,
                () -> new EnrollmentData(UUID.randomUUID(), null, person(), address("DE"), address("DE")));
    }

    // ── Address ───────────────────────────────────────────────────────────────

    @Test
    void address_roundTrip() throws Exception {
        var original = new Address(List.of("Line 1", "Line 2"), "10115", "Berlin", "BE", "DE");
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, Address.class);
        assertEquals(original, deserialized);
    }

    @Test
    void address_nullCountryCode_throws() {
        assertThrows(NullPointerException.class,
                () -> new Address(List.of("123 Main St"), "10115", "Berlin", "BE", null));
    }

    @Test
    void address_nullStreetLines_defaultsToEmpty() {
        var addr = new Address(null, "10115", "Berlin", "BE", "DE");
        assertEquals(List.of(), addr.streetLines());
    }

    // ── GeoScoreResult ────────────────────────────────────────────────────────

    @Test
    void geoScoreResult_withRiskLevel_roundTrip() throws Exception {
        var original = new GeoScoreResult(
                UUID.randomUUID(), RiskLevel.HIGH, null,
                Map.of(100, 3, 250, 7), List.of(100, 250),
                52.52, 13.405);
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, GeoScoreResult.class);
        assertEquals(original, deserialized);
    }

    @Test
    void geoScoreResult_extreme_roundTrip() throws Exception {
        var original = new GeoScoreResult(
                UUID.randomUUID(), RiskLevel.EXTREME, null,
                Map.of(100, 200), List.of(100),
                52.52, 13.405);
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, GeoScoreResult.class);
        assertEquals(original, deserialized);
    }

    @Test
    void geoScoreResult_noResult_geocodingFailed() throws Exception {
        var original = new GeoScoreResult(
                UUID.randomUUID(), null, "geocoding_failed",
                null, null, null, null);
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, GeoScoreResult.class);
        assertEquals(original, deserialized);
    }

    @Test
    void geoScoreResult_nullEnrollmentId_throws() {
        assertThrows(NullPointerException.class,
                () -> new GeoScoreResult(null, RiskLevel.LOW, null, Map.of(), List.of(), null, null));
    }

    @Test
    void geoScoreResult_nullCollections_defaultToEmpty() {
        var event = new GeoScoreResult(UUID.randomUUID(), RiskLevel.MEDIUM, null, null, null, null, null);
        assertEquals(Map.of(), event.neighborCounts());
        assertEquals(List.of(), event.triggeredThresholds());
    }

    // ── FraudCheckResult ──────────────────────────────────────────────────────

    @Test
    void fraudCheckResult_roundTrip() throws Exception {
        var original = new FraudCheckResult(UUID.randomUUID(), SignalOutcome.OK);
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, FraudCheckResult.class);
        assertEquals(original, deserialized);
    }

    @Test
    void fraudCheckResult_allOutcomes_roundTrip() throws Exception {
        for (var outcome : SignalOutcome.values()) {
            var original = new FraudCheckResult(UUID.randomUUID(), outcome);
            var json = mapper.writeValueAsString(original);
            var deserialized = mapper.readValue(json, FraudCheckResult.class);
            assertEquals(original, deserialized);
        }
    }

    @Test
    void fraudCheckResult_nullEnrollmentId_throws() {
        assertThrows(NullPointerException.class,
                () -> new FraudCheckResult(null, SignalOutcome.OK));
    }

    @Test
    void fraudCheckResult_nullOutcome_throws() {
        assertThrows(NullPointerException.class,
                () -> new FraudCheckResult(UUID.randomUUID(), null));
    }

    // ── EnrollmentDecisionEvent ───────────────────────────────────────────────

    @Test
    void enrollmentDecisionEvent_approved_roundTrip() throws Exception {
        var now = Instant.now();
        var signals = Map.of(
                "FRAUD_CHECK", new EnrollmentSignal(SignalOutcome.OK, null, null),
                "GEO_SCORE",   new EnrollmentSignal(null, RiskLevel.LOW, null));
        var original = new EnrollmentDecisionEvent(UUID.randomUUID(), enrollmentData(PaymentType.CREDIT_CARD),
                DecisionResult.APPROVED, signals, now);
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, EnrollmentDecisionEvent.class);
        assertEquals(original, deserialized);
    }

    @Test
    void enrollmentDecisionEvent_rejected_roundTrip() throws Exception {
        var now = Instant.now();
        var signals = Map.of("FRAUD_CHECK", new EnrollmentSignal(SignalOutcome.FAILED, null, null));
        var original = new EnrollmentDecisionEvent(UUID.randomUUID(), enrollmentData(PaymentType.CREDIT_CARD),
                DecisionResult.REJECTED, signals, now);
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, EnrollmentDecisionEvent.class);
        assertEquals(original, deserialized);
    }

    @Test
    void enrollmentDecisionEvent_conditionalApproved_roundTrip() throws Exception {
        var now = Instant.now();
        var signals = Map.of(
                "FRAUD_CHECK", new EnrollmentSignal(SignalOutcome.OK, null, null),
                "GEO_SCORE",   new EnrollmentSignal(null, RiskLevel.HIGH, null));
        var original = new EnrollmentDecisionEvent(UUID.randomUUID(), enrollmentData(PaymentType.CREDIT_CARD),
                DecisionResult.CONDITIONAL_APPROVED, signals, now);
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, EnrollmentDecisionEvent.class);
        assertEquals(original, deserialized);
    }

    @Test
    void enrollmentDecisionEvent_signalWithTimeout_roundTrip() throws Exception {
        var signals = Map.of("GEO_SCORE", new EnrollmentSignal(null, null, "timeout"));
        var original = new EnrollmentDecisionEvent(UUID.randomUUID(), enrollmentData(PaymentType.INVOICE),
                DecisionResult.APPROVED, signals, Instant.now());
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, EnrollmentDecisionEvent.class);
        assertEquals(original, deserialized);
    }

    @Test
    void enrollmentDecisionEvent_nullDecisionId_throws() {
        assertThrows(NullPointerException.class, () -> new EnrollmentDecisionEvent(
                null, enrollmentData(PaymentType.CREDIT_CARD), DecisionResult.APPROVED, Map.of(), Instant.now()));
    }

    @Test
    void enrollmentDecisionEvent_nullOriginalRequest_throws() {
        assertThrows(NullPointerException.class, () -> new EnrollmentDecisionEvent(
                UUID.randomUUID(), null, DecisionResult.APPROVED, Map.of(), Instant.now()));
    }

    @Test
    void enrollmentDecisionEvent_nullDecisionResult_throws() {
        assertThrows(NullPointerException.class, () -> new EnrollmentDecisionEvent(
                UUID.randomUUID(), enrollmentData(PaymentType.CREDIT_CARD), null, Map.of(), Instant.now()));
    }

    @Test
    void enrollmentDecisionEvent_nullSignals_throws() {
        assertThrows(NullPointerException.class, () -> new EnrollmentDecisionEvent(
                UUID.randomUUID(), enrollmentData(PaymentType.CREDIT_CARD), DecisionResult.APPROVED, null, Instant.now()));
    }

    @Test
    void enrollmentDecisionEvent_nullDecidedAt_throws() {
        assertThrows(NullPointerException.class, () -> new EnrollmentDecisionEvent(
                UUID.randomUUID(), enrollmentData(PaymentType.CREDIT_CARD), DecisionResult.APPROVED, Map.of(), null));
    }
}
