package dev.sindic.enrollmenthub.contracts.events;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.domain.EnrollmentSnapshot;
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

    // fixtures

    private static Address address(String countryCode) {
        return new Address(List.of("123 Main St"), "10115", "Berlin", "BE", countryCode);
    }

    private static Person person() {
        return new Person("Jane", "Doe", "jane@example.com", "+491234567890");
    }

    private static EnrollmentData enrollmentData(PaymentType paymentType) {
        return new EnrollmentData(UUID.randomUUID(), paymentType, person(), address("DE"), address("DE"));
    }

    private static EnrollmentSnapshot enrollmentSnapshot(PaymentType paymentType) {
        return new EnrollmentSnapshot(paymentType, person(), address("DE"), address("DE"));
    }

    private static final Instant FIXED_CREATED_AT = Instant.parse("2026-05-23T10:00:00Z");

    // ── Check request commands ────────────────────────────────────────────────

    @Test
    void geoScoreRequest_roundTrip() throws Exception {
        var original = new GeoScoreRequest(UUID.randomUUID(), address("DE"));
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, GeoScoreRequest.class);
        assertEquals(original, deserialized);
    }

    @Test
    void geoScoreRequest_nullShippingAddress_throws() {
        assertThrows(NullPointerException.class,
                () -> new GeoScoreRequest(UUID.randomUUID(), null));
    }

    @Test
    void fraudCheckRequest_roundTrip_exposesEnrollmentId() throws Exception {
        var data = enrollmentData(PaymentType.CREDIT_CARD);
        var original = new FraudCheckRequest(data);
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, FraudCheckRequest.class);
        assertEquals(original, deserialized);
        assertEquals(data.enrollmentId(), deserialized.enrollmentId());
    }

    @Test
    void fraudCheckRequest_nullData_throws() {
        assertThrows(NullPointerException.class, () -> new FraudCheckRequest(null));
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
                Map.of(100, 3, 250, 7), List.of(100, 250));
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, GeoScoreResult.class);
        assertEquals(original, deserialized);
    }

    @Test
    void geoScoreResult_extreme_roundTrip() throws Exception {
        var original = new GeoScoreResult(
                UUID.randomUUID(), RiskLevel.EXTREME, null,
                Map.of(100, 200), List.of(100));
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, GeoScoreResult.class);
        assertEquals(original, deserialized);
    }

    @Test
    void geoScoreResult_noResult_geocodingFailed() throws Exception {
        var original = new GeoScoreResult(
                UUID.randomUUID(), null, "geocoding_failed",
                null, null);
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, GeoScoreResult.class);
        assertEquals(original, deserialized);
    }

    @Test
    void fraudCheckResult_noResultWithoutReason_throws() {
        // A missing verdict is not worth much without one — the same rule GeoScoreResult applies.
        assertThrows(IllegalArgumentException.class,
                () -> new FraudCheckResult(UUID.randomUUID(), CheckOutcome.NO_RESULT, null));
    }

    @Test
    void fraudCheckResult_verdictWithAReason_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new FraudCheckResult(UUID.randomUUID(), CheckOutcome.OK, "provider_unavailable"));
    }

    @Test
    void fraudCheckResult_noResultReasonReachesThePublishedSignal() throws Exception {
        // The point of the field: an operator can tell a provider outage from a thin file.
        var original = FraudCheckResult.noResult(UUID.randomUUID(), "provider_unavailable");
        var json = mapper.writeValueAsString(original);

        assertEquals(original, mapper.readValue(json, FraudCheckResult.class));
        assertEquals("provider_unavailable", original.noResultReason());
    }

    @Test
    void geoScoreResult_noRiskLevelAndNoReason_throws() {
        // Without this the listener stores NoResult(null), which publishes fine until
        // EnrollmentSignal.noResult rejects it — inside the dispatcher, after the decision is
        // committed, where the relay then retries the same row every tick.
        assertThrows(IllegalArgumentException.class,
                () -> new GeoScoreResult(UUID.randomUUID(), null, null, Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new GeoScoreResult(UUID.randomUUID(), null, "  ", Map.of(), List.of()));
    }

    @Test
    void geoScoreResult_bothRiskLevelAndReason_throws() {
        // The other half of the XOR. Unguarded, the listener maps this to Scored and silently drops
        // the reason — a contradiction resolved by discarding half of it.
        assertThrows(IllegalArgumentException.class,
                () -> new GeoScoreResult(UUID.randomUUID(), RiskLevel.HIGH, "geocoding_failed",
                        Map.of(), List.of()));
    }

    @Test
    void geoScoreResult_nullEnrollmentId_throws() {
        assertThrows(NullPointerException.class,
                () -> new GeoScoreResult(null, RiskLevel.LOW, null, Map.of(), List.of()));
    }

    @Test
    void geoScoreResult_nullCollections_defaultToEmpty() {
        var event = new GeoScoreResult(UUID.randomUUID(), RiskLevel.MEDIUM, null, null, null);
        assertEquals(Map.of(), event.neighborCounts());
        assertEquals(List.of(), event.triggeredThresholds());
    }

    // ── FraudCheckResult ──────────────────────────────────────────────────────

    @Test
    void fraudCheckResult_roundTrip() throws Exception {
        var original = FraudCheckResult.checked(UUID.randomUUID(), CheckOutcome.OK);
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, FraudCheckResult.class);
        assertEquals(original, deserialized);
    }

    @Test
    void fraudCheckResult_allOutcomes_roundTrip() throws Exception {
        // Every value round-trips, and every value is meaningful: a worker's vocabulary has no
        // term for "no reply arrived", so there is nothing here to reject.
        for (var outcome : CheckOutcome.values()) {
            var original = outcome == CheckOutcome.NO_RESULT
                    ? FraudCheckResult.noResult(UUID.randomUUID(), "provider_unavailable")
                    : FraudCheckResult.checked(UUID.randomUUID(), outcome);
            var json = mapper.writeValueAsString(original);
            var deserialized = mapper.readValue(json, FraudCheckResult.class);
            assertEquals(original, deserialized);
        }
    }

    @Test
    void fraudCheckResult_nullEnrollmentId_throws() {
        assertThrows(NullPointerException.class,
                () -> FraudCheckResult.checked(null, CheckOutcome.OK));
    }

    @Test
    void fraudCheckResult_nullOutcome_throws() {
        assertThrows(NullPointerException.class,
                () -> FraudCheckResult.checked(UUID.randomUUID(), null));
    }

    // ── EnrollmentSignal ──────────────────────────────────────────────────────

    @Test
    void enrollmentSignal_factoriesProduceTheDocumentedShapes() {
        assertEquals(new EnrollmentSignal(SignalOutcome.OK, null, null),
                EnrollmentSignal.checked(SignalOutcome.OK));
        assertEquals(new EnrollmentSignal(null, RiskLevel.HIGH, null),
                EnrollmentSignal.scored(RiskLevel.HIGH));
        assertEquals(new EnrollmentSignal(null, null, "geocoding_failed"),
                EnrollmentSignal.noResult("geocoding_failed"));
        assertEquals(new EnrollmentSignal(SignalOutcome.NOT_EXECUTED, null, "timeout"),
                EnrollmentSignal.notExecuted("timeout"));
    }

    @Test
    void enrollmentSignal_ranWithoutResult_isDistinctFromNeverAnswered() {
        // The pair this contract exists to keep apart. Same null riskLevel, different outcome.
        var ran      = EnrollmentSignal.noResult("geocoding_failed");
        var neverRan = EnrollmentSignal.notExecuted("timeout");

        assertNull(ran.outcome());
        assertEquals(SignalOutcome.NOT_EXECUTED, neverRan.outcome());
        assertNotEquals(ran, neverRan);
    }

    @Test
    void enrollmentSignal_bothResultFields_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new EnrollmentSignal(SignalOutcome.OK, RiskLevel.HIGH, null));
    }

    @Test
    void enrollmentSignal_notExecutedWithoutReason_throws() {
        // The reason is the only record of why the signal is missing.
        assertThrows(IllegalArgumentException.class,
                () -> new EnrollmentSignal(SignalOutcome.NOT_EXECUTED, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new EnrollmentSignal(SignalOutcome.NOT_EXECUTED, null, "  "));
    }

    @Test
    void enrollmentSignal_checkedRejectsNotExecuted() {
        assertThrows(IllegalArgumentException.class,
                () -> EnrollmentSignal.checked(SignalOutcome.NOT_EXECUTED));
    }

    @Test
    void enrollmentSignal_notExecuted_roundTrips() throws Exception {
        var original = EnrollmentSignal.notExecuted("timeout");
        var json = mapper.writeValueAsString(original);
        assertEquals(original, mapper.readValue(json, EnrollmentSignal.class));
    }

    @Test
    void enrollmentSignal_wireShapeIsPinned() throws Exception {
        // Round-trip and record equality both survive a change to the wire form — a naming
        // strategy, a @JsonProperty, a renamed constant. Consumers parse these bytes, so assert
        // the bytes. ADR-06 permits added fields; it does not permit renamed ones.
        assertEquals("{\"outcome\":\"NOT_EXECUTED\",\"riskLevel\":null,\"reason\":\"timeout\"}",
                mapper.writeValueAsString(EnrollmentSignal.notExecuted("timeout")));
        assertEquals("{\"outcome\":null,\"riskLevel\":null,\"reason\":\"geocoding_failed\"}",
                mapper.writeValueAsString(EnrollmentSignal.noResult("geocoding_failed")));
        assertEquals("{\"outcome\":\"OK\",\"riskLevel\":null,\"reason\":null}",
                mapper.writeValueAsString(EnrollmentSignal.checked(SignalOutcome.OK)));
        assertEquals("{\"outcome\":null,\"riskLevel\":\"HIGH\",\"reason\":null}",
                mapper.writeValueAsString(EnrollmentSignal.scored(RiskLevel.HIGH)));
    }

    // ── EnrollmentDecisionEvent ───────────────────────────────────────────────

    @Test
    void enrollmentDecisionEvent_approved_roundTrip() throws Exception {
        var now = Instant.now();
        var signals = Map.of(
                "FRAUD_CHECK", new EnrollmentSignal(SignalOutcome.OK, null, null),
                "GEO_SCORE",   new EnrollmentSignal(null, RiskLevel.LOW, null));
        var original = new EnrollmentDecisionEvent(UUID.randomUUID(), enrollmentSnapshot(PaymentType.CREDIT_CARD),
                DecisionResult.APPROVED, signals, now);
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, EnrollmentDecisionEvent.class);
        assertEquals(original, deserialized);
    }

    @Test
    void enrollmentDecisionEvent_rejected_roundTrip() throws Exception {
        var now = Instant.now();
        var signals = Map.of("FRAUD_CHECK", new EnrollmentSignal(SignalOutcome.FAILED, null, null));
        var original = new EnrollmentDecisionEvent(UUID.randomUUID(), enrollmentSnapshot(PaymentType.CREDIT_CARD),
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
        var original = new EnrollmentDecisionEvent(UUID.randomUUID(), enrollmentSnapshot(PaymentType.CREDIT_CARD),
                DecisionResult.CONDITIONAL_APPROVED, signals, now);
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, EnrollmentDecisionEvent.class);
        assertEquals(original, deserialized);
    }

    @Test
    void enrollmentDecisionEvent_signalWithTimeout_roundTrip() throws Exception {
        // A timed-out signal publishes NOT_EXECUTED with the reason — not a bare reason string,
        // which is the "ran, produced nothing" shape and means something else (ADR-14).
        var signals = Map.of("GEO_SCORE", EnrollmentSignal.notExecuted("timeout"));
        var original = new EnrollmentDecisionEvent(UUID.randomUUID(), enrollmentSnapshot(PaymentType.INVOICE),
                DecisionResult.APPROVED, signals, Instant.now());
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, EnrollmentDecisionEvent.class);
        assertEquals(original, deserialized);
    }

    @Test
    void enrollmentDecisionEvent_json_carriesNoEnrollmentId() throws Exception {
        // ADR-17 dedup-key decision: the decision event identifies itself by decisionId only;
        // the correlation enrollmentId is the decision-engine's internal primary key and does
        // not appear anywhere in the serialized payload.
        var original = new EnrollmentDecisionEvent(UUID.randomUUID(),
                enrollmentSnapshot(PaymentType.CREDIT_CARD),
                DecisionResult.APPROVED,
                Map.of("FRAUD_CHECK", new EnrollmentSignal(SignalOutcome.OK, null, null)),
                Instant.now());
        var json = mapper.writeValueAsString(original);
        assertFalse(json.contains("enrollmentId"), "decision event must not carry the correlation id");
    }

    @Test
    void enrollmentDecisionEvent_nullDecisionId_throws() {
        assertThrows(NullPointerException.class, () -> new EnrollmentDecisionEvent(
                null, enrollmentSnapshot(PaymentType.CREDIT_CARD), DecisionResult.APPROVED, Map.of(), Instant.now()));
    }

    @Test
    void enrollmentDecisionEvent_nullOriginalRequest_throws() {
        assertThrows(NullPointerException.class, () -> new EnrollmentDecisionEvent(
                UUID.randomUUID(), null, DecisionResult.APPROVED, Map.of(), Instant.now()));
    }

    @Test
    void enrollmentDecisionEvent_nullDecisionResult_throws() {
        assertThrows(NullPointerException.class, () -> new EnrollmentDecisionEvent(
                UUID.randomUUID(), enrollmentSnapshot(PaymentType.CREDIT_CARD), null, Map.of(), Instant.now()));
    }

    @Test
    void enrollmentDecisionEvent_nullSignals_throws() {
        assertThrows(NullPointerException.class, () -> new EnrollmentDecisionEvent(
                UUID.randomUUID(), enrollmentSnapshot(PaymentType.CREDIT_CARD), DecisionResult.APPROVED, null, Instant.now()));
    }

    @Test
    void enrollmentDecisionEvent_nullDecidedAt_throws() {
        assertThrows(NullPointerException.class, () -> new EnrollmentDecisionEvent(
                UUID.randomUUID(), enrollmentSnapshot(PaymentType.CREDIT_CARD), DecisionResult.APPROVED, Map.of(), null));
    }
}
