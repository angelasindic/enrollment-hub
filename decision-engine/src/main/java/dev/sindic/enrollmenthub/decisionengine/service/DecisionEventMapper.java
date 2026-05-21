package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.events.DecisionResult;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentDecisionEvent;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentSignal;
import dev.sindic.enrollmenthub.contracts.events.RiskLevel;
import dev.sindic.enrollmenthub.contracts.events.SignalOutcome;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalProcessingState;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentEntity;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;

final class DecisionEventMapper {

    private final JsonMapper jsonMapper;

    DecisionEventMapper(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    EnrollmentDecisionEvent buildDecisionEvent(EnrollmentEntity entity) {
        var signals = new HashMap<String, EnrollmentSignal>();
        for (var entry : entity.getSignals().entrySet()) {
            signals.put(entry.getKey().name(), toContractSignal(entry.getValue()));
        }
        return new EnrollmentDecisionEvent(
                entity.getDecisionId(),
                jsonMapper.readValue(entity.getOriginalRequest(), EnrollmentData.class),
                DecisionResult.valueOf(entity.getDecisionResult().name()),
                signals,
                entity.getDecidedAt());
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
