package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.domain.EnrollmentSnapshot;
import dev.sindic.enrollmenthub.contracts.events.DecisionResult;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentDecisionEvent;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentSignal;
import dev.sindic.enrollmenthub.contracts.events.RiskLevel;
import dev.sindic.enrollmenthub.contracts.events.SignalOutcome;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalProcessingState;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentEntity;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the outbound {@link EnrollmentDecisionEvent} from the persisted, frozen decision
 * state (ADR-17: delivery replays the persisted decision and never recomputes it).
 *
 * <p>Invoked by {@code DecisionDispatcher} with values read from a freshly-loaded row.
 * They are passed as explicit parameters rather than plucked from the entity here so the
 * mapper stays a pure function testable without persistence.
 *
 * <p>The stored {@code original_request} is deserialised into the id-carrying intake payload
 * ({@link EnrollmentData}) and mapped down to {@link EnrollmentSnapshot}: the correlation
 * {@code enrollmentId} is the database primary key and must not leave the service — consumers
 * deduplicate on {@code decisionId}, which is frozen at decide time.
 */
final class DecisionEventMapper {

    private final JsonMapper jsonMapper;

    DecisionEventMapper(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    EnrollmentDecisionEvent buildDecisionEvent(
            EnrollmentEntity entity,
            Map<SignalConfig, SignalState> signals,
            dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult decision,
            UUID decisionId,
            Instant decidedAt) {

        var contractSignals = new HashMap<String, EnrollmentSignal>();
        for (var entry : signals.entrySet()) {
            contractSignals.put(entry.getKey().name(), toContractSignal(entry.getValue()));
        }
        return new EnrollmentDecisionEvent(
                decisionId,
                toSnapshot(jsonMapper.readValue(entity.getOriginalRequest(), EnrollmentData.class)),
                DecisionResult.valueOf(decision.name()),
                contractSignals,
                decidedAt);
    }

    private static EnrollmentSnapshot toSnapshot(EnrollmentData data) {
        return new EnrollmentSnapshot(
                data.paymentType(), data.person(), data.shippingAddress(), data.billingAddress());
    }

    private static EnrollmentSignal toContractSignal(SignalState state) {
        if (state.processingState() == SignalProcessingState.FAILED) {
            return new EnrollmentSignal(SignalOutcome.FAILED, mapRiskLevel(state.riskLevel()), state.reason());
        }
        SignalOutcome outcome = state.outcome() != null ? SignalOutcome.valueOf(state.outcome().name()) : null;
        RiskLevel riskLevel = mapRiskLevel(state.riskLevel());
        return new EnrollmentSignal(outcome, riskLevel, state.reason());
    }

    private static RiskLevel mapRiskLevel(dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel riskLevel) {
        return riskLevel != null ? RiskLevel.valueOf(riskLevel.name()) : null;
    }
}
