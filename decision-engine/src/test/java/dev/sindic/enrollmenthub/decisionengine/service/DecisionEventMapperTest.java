package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;
import dev.sindic.enrollmenthub.decisionengine.TestEntityFactory;
import dev.sindic.enrollmenthub.contracts.events.DecisionResult;
import dev.sindic.enrollmenthub.contracts.events.RiskLevel;
import dev.sindic.enrollmenthub.contracts.events.SignalOutcome;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentSignal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionEventMapperTest {

    private static final Instant NOW = Instant.parse("2026-04-13T12:00:00Z");
    private static final Instant TIMEOUT = NOW.plusSeconds(60);
    private static final Instant DECIDED_AT = NOW.plusSeconds(5);

    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    private final DecisionEventMapper mapper = new DecisionEventMapper(JSON);

    @Test
    void mapsApprovedCreditCard() {
        var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
        var signals = creditCardSignals(
                new SignalState.Scored(dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel.LOW),
                new SignalState.Checked(dev.sindic.enrollmenthub.decisionengine.domain.CheckOutcome.OK));

        var event = mapper.buildDecisionEvent(entity, signals, approved(), UUID.randomUUID(), DECIDED_AT);

        assertThat(event.decisionResult()).isEqualTo(DecisionResult.APPROVED);
        assertThat(event.signals().get("GEO_SCORE").riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(event.signals().get("GEO_SCORE").outcome()).isNull();
        assertThat(event.signals().get("FRAUD_CHECK").outcome()).isEqualTo(SignalOutcome.OK);
        assertThat(event.signals().get("FRAUD_CHECK").riskLevel()).isNull();
    }

    @Test
    void mapsConditionalApprovedCreditCard() {
        var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
        var signals = creditCardSignals(
                new SignalState.Scored(dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel.HIGH),
                new SignalState.Checked(dev.sindic.enrollmenthub.decisionengine.domain.CheckOutcome.OK));

        var event = mapper.buildDecisionEvent(entity, signals,
                dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult.CONDITIONAL_APPROVED,
                UUID.randomUUID(), DECIDED_AT);

        assertThat(event.decisionResult()).isEqualTo(DecisionResult.CONDITIONAL_APPROVED);
        assertThat(event.signals().get("GEO_SCORE").riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void mapsRejectedCreditCard() {
        var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
        var signals = creditCardSignals(
                new SignalState.Scored(dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel.LOW),
                new SignalState.Checked(dev.sindic.enrollmenthub.decisionengine.domain.CheckOutcome.FAILED));

        var event = mapper.buildDecisionEvent(entity, signals,
                dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult.REJECTED,
                UUID.randomUUID(), DECIDED_AT);

        assertThat(event.decisionResult()).isEqualTo(DecisionResult.REJECTED);
        assertThat(event.signals().get("FRAUD_CHECK").outcome()).isEqualTo(SignalOutcome.FAILED);
    }

    /**
     * C1: a signal that never answered used to publish as {@code FAILED}, so an APPROVED decision
     * carried an adverse outcome for the check that caused the approval to be safe. ADR-14:55
     * requires "did not respond" stay distinguishable from "responded without a result".
     */
    @Test
    void geoScoreNeverAnswered_publishesNotExecutedWithTheReason() {
        var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
        var signals = creditCardSignals(
                new SignalState.NotExecuted("timeout"),
                new SignalState.Checked(dev.sindic.enrollmenthub.decisionengine.domain.CheckOutcome.OK));

        var event = mapper.buildDecisionEvent(entity, signals, approved(), UUID.randomUUID(), DECIDED_AT);

        assertThat(event.signals().get("GEO_SCORE"))
                .isEqualTo(EnrollmentSignal.notExecuted("timeout"));
    }

    @Test
    void neverAnsweredAndRanWithoutResult_publishDifferently() {
        // Both fail open and both approve, so only the published signal tells them apart.
        var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
        var ok = new SignalState.Checked(dev.sindic.enrollmenthub.decisionengine.domain.CheckOutcome.OK);

        var neverRan = mapper.buildDecisionEvent(entity,
                creditCardSignals(new SignalState.NotExecuted("timeout"), ok),
                approved(), UUID.randomUUID(), DECIDED_AT).signals().get("GEO_SCORE");
        var ran = mapper.buildDecisionEvent(entity,
                creditCardSignals(new SignalState.NoResult("geocoding_failed"), ok),
                approved(), UUID.randomUUID(), DECIDED_AT).signals().get("GEO_SCORE");

        assertThat(neverRan.outcome()).isEqualTo(SignalOutcome.NOT_EXECUTED);
        assertThat(ran.outcome()).isNull();
        assertThat(neverRan).isNotEqualTo(ran);
    }

    @Test
    void geoScoreNoResult_producesNullSignalFields() {
        var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
        var signals = creditCardSignals(
                new SignalState.NoResult("geocoding_failed"),
                new SignalState.Checked(dev.sindic.enrollmenthub.decisionengine.domain.CheckOutcome.OK));

        var event = mapper.buildDecisionEvent(entity, signals, approved(), UUID.randomUUID(), DECIDED_AT);

        var geoSignal = event.signals().get("GEO_SCORE");
        assertThat(geoSignal.riskLevel()).isNull();
        assertThat(geoSignal.outcome()).isNull();
    }

    @Test
    void invoiceRoute_onlyFraudSignalPresent() {
        var entity = TestEntityFactory.invoice(UUID.randomUUID(), NOW, TIMEOUT);
        var signals = new EnumMap<SignalConfig, SignalState>(SignalConfig.class);
        signals.put(SignalConfig.FRAUD_CHECK,
                new SignalState.Checked(dev.sindic.enrollmenthub.decisionengine.domain.CheckOutcome.OK));

        var event = mapper.buildDecisionEvent(entity, signals, approved(), UUID.randomUUID(), DECIDED_AT);

        assertThat(event.signals()).containsOnlyKeys("FRAUD_CHECK");
        assertThat(event.decisionResult()).isEqualTo(DecisionResult.APPROVED);
    }

    @Test
    void carriesDecisionIdAndOriginalRequestAndDecidedAt() {
        var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
        var signals = creditCardSignals(
                new SignalState.Scored(dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel.LOW),
                new SignalState.Checked(dev.sindic.enrollmenthub.decisionengine.domain.CheckOutcome.OK));
        var decisionId = UUID.randomUUID();

        var event = mapper.buildDecisionEvent(entity, signals, approved(), decisionId, DECIDED_AT);

        assertThat(event.decisionId()).isEqualTo(decisionId);
        assertThat(event.originalRequest()).isNotNull();
        assertThat(event.originalRequest().paymentType())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.domain.PaymentType.CREDIT_CARD);
        assertThat(event.decidedAt()).isEqualTo(DECIDED_AT);
    }

    @Test
    void eventJson_neverContainsTheCorrelationEnrollmentId() {
        // ADR-17 dedup-key decision: the correlation enrollmentId is the DB primary key and
        // must not leave the service. The stored original_request JSON carries it (correct —
        // that is the database's copy); the published snapshot must not. Assert at the JSON
        // level: what matters is the bytes that reach the broker, not the record type.
        var enrollmentId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(enrollmentId, NOW, TIMEOUT);
        var signals = creditCardSignals(
                new SignalState.Scored(dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel.LOW),
                new SignalState.Checked(dev.sindic.enrollmenthub.decisionengine.domain.CheckOutcome.OK));

        var event = mapper.buildDecisionEvent(entity, signals, approved(), UUID.randomUUID(), DECIDED_AT);
        var json = JSON.writeValueAsString(event);

        assertThat(entity.getOriginalRequest()).contains(enrollmentId.toString());
        assertThat(json)
                .doesNotContain(enrollmentId.toString())
                .doesNotContain("enrollmentId");
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static Map<SignalConfig, SignalState> creditCardSignals(
            SignalState geoState, SignalState fraudState) {
        var signals = new EnumMap<SignalConfig, SignalState>(SignalConfig.class);
        signals.put(SignalConfig.GEO_SCORE,   geoState);
        signals.put(SignalConfig.FRAUD_CHECK, fraudState);
        return signals;
    }

    private static dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult approved() {
        return dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult.APPROVED;
    }
}
