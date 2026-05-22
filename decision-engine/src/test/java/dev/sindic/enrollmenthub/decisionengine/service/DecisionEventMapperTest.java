package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.decisionengine.domain.EnrollmentDecisionResult;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentEntity;
import dev.sindic.enrollmenthub.decisionengine.TestEntityFactory;
import dev.sindic.enrollmenthub.contracts.events.DecisionResult;
import dev.sindic.enrollmenthub.contracts.events.RiskLevel;
import dev.sindic.enrollmenthub.contracts.events.SignalOutcome;
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

    private final DecisionEventMapper mapper =
            new DecisionEventMapper(JsonMapper.builder().findAndAddModules().build());

    @Test
    void mapsApprovedCreditCard() {
        var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
        var signals = creditCardSignals(
                SignalState.settled(dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel.LOW),
                SignalState.settled(dev.sindic.enrollmenthub.decisionengine.domain.SignalOutcome.OK));
        var decision = approved();

        var event = mapper.buildDecisionEvent(entity, signals, decision, UUID.randomUUID(), DECIDED_AT);

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
                SignalState.settled(dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel.HIGH),
                SignalState.settled(dev.sindic.enrollmenthub.decisionengine.domain.SignalOutcome.OK));
        var decision = new EnrollmentDecisionResult(
                dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult.CONDITIONAL_APPROVED);

        var event = mapper.buildDecisionEvent(entity, signals, decision, UUID.randomUUID(), DECIDED_AT);

        assertThat(event.decisionResult()).isEqualTo(DecisionResult.CONDITIONAL_APPROVED);
        assertThat(event.signals().get("GEO_SCORE").riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void mapsRejectedCreditCard() {
        var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
        var signals = creditCardSignals(
                SignalState.settled(dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel.LOW),
                SignalState.settled(dev.sindic.enrollmenthub.decisionengine.domain.SignalOutcome.FAILED));
        var decision = new EnrollmentDecisionResult(
                dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult.REJECTED);

        var event = mapper.buildDecisionEvent(entity, signals, decision, UUID.randomUUID(), DECIDED_AT);

        assertThat(event.decisionResult()).isEqualTo(DecisionResult.REJECTED);
        assertThat(event.signals().get("FRAUD_CHECK").outcome()).isEqualTo(SignalOutcome.FAILED);
    }

    @Test
    void geoScoreFailed_producesSignalFieldFailed() {
        var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
        var signals = creditCardSignals(
                SignalState.failed(),
                SignalState.settled(dev.sindic.enrollmenthub.decisionengine.domain.SignalOutcome.OK));

        var event = mapper.buildDecisionEvent(entity, signals, approved(), UUID.randomUUID(), DECIDED_AT);

        var geoSignal = event.signals().get("GEO_SCORE");
        assertThat(geoSignal.riskLevel()).isNull();
        assertThat(geoSignal.outcome()).isEqualTo(SignalOutcome.FAILED);
    }

    @Test
    void geoScoreNoResult_producesNullSignalFields() {
        var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
        var signals = creditCardSignals(
                SignalState.settledWithoutResult("geocoding_failed"),
                SignalState.settled(dev.sindic.enrollmenthub.decisionengine.domain.SignalOutcome.OK));

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
                SignalState.settled(dev.sindic.enrollmenthub.decisionengine.domain.SignalOutcome.OK));

        var event = mapper.buildDecisionEvent(entity, signals, approved(), UUID.randomUUID(), DECIDED_AT);

        assertThat(event.signals()).containsOnlyKeys("FRAUD_CHECK");
        assertThat(event.decisionResult()).isEqualTo(DecisionResult.APPROVED);
    }

    @Test
    void carriesDecisionIdAndOriginalRequestAndDecidedAt() {
        var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
        var signals = creditCardSignals(
                SignalState.settled(dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel.LOW),
                SignalState.settled(dev.sindic.enrollmenthub.decisionengine.domain.SignalOutcome.OK));
        var decisionId = UUID.randomUUID();

        var event = mapper.buildDecisionEvent(entity, signals, approved(), decisionId, DECIDED_AT);

        assertThat(event.decisionId()).isEqualTo(decisionId);
        assertThat(event.originalRequest()).isNotNull();
        assertThat(event.decidedAt()).isEqualTo(DECIDED_AT);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static Map<SignalConfig, SignalState> creditCardSignals(
            SignalState geoState, SignalState fraudState) {
        var signals = new EnumMap<SignalConfig, SignalState>(SignalConfig.class);
        signals.put(SignalConfig.GEO_SCORE,   geoState);
        signals.put(SignalConfig.FRAUD_CHECK, fraudState);
        return signals;
    }

    private static EnrollmentDecisionResult approved() {
        return new EnrollmentDecisionResult(
                dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult.APPROVED);
    }
}
