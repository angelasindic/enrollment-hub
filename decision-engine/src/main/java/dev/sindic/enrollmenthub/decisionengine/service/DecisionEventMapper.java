package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.events.DecisionResult;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentDecisionEvent;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentSignal;
import dev.sindic.enrollmenthub.contracts.events.RiskLevel;
import dev.sindic.enrollmenthub.contracts.events.SignalOutcome;
import dev.sindic.enrollmenthub.decisionengine.domain.EnrollmentDecisionResult;
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
 * Builds the outbound {@link EnrollmentDecisionEvent} from the just-decided
 * state.
 *
 * <p>Inputs are passed in explicitly (signals map, decision, decisionId,
 * decidedAt) rather than read from the entity, because the entity's
 * in-memory state is stale relative to the JSON-column {@code UPDATE} the
 * service issued moments earlier (ADR-015 §Write path: explicit {@code UPDATE} for the
 * {@code signals} column; the loaded entity is not refreshed). The entity is
 * still the source of truth for fields this service path did not write — the
 * immutable {@code original_request} column.
 */
final class DecisionEventMapper {

    private final JsonMapper jsonMapper;

    DecisionEventMapper(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    EnrollmentDecisionEvent buildDecisionEvent(
            EnrollmentEntity entity,
            Map<SignalConfig, SignalState> signals,
            EnrollmentDecisionResult decision,
            UUID decisionId,
            Instant decidedAt) {

        var contractSignals = new HashMap<String, EnrollmentSignal>();
        for (var entry : signals.entrySet()) {
            contractSignals.put(entry.getKey().name(), toContractSignal(entry.getValue()));
        }
        return new EnrollmentDecisionEvent(
                decisionId,
                jsonMapper.readValue(entity.getOriginalRequest(), EnrollmentData.class),
                DecisionResult.valueOf(decision.decision().name()),
                contractSignals,
                decidedAt);
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
