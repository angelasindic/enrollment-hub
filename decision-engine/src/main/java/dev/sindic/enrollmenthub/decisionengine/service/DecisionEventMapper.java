package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.domain.EnrollmentSnapshot;
import dev.sindic.enrollmenthub.contracts.events.DecisionResult;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentDecisionEvent;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentSignal;
import dev.sindic.enrollmenthub.contracts.events.RiskLevel;
import dev.sindic.enrollmenthub.contracts.events.SignalOutcome;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
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

    /**
     * Flattens a {@link SignalState} onto the published three-field {@link EnrollmentSignal}.
     *
     * <p>Exhaustive by design — a sixth variant stops this compiling. {@code NotExecuted} still
     * publishes as {@link SignalOutcome#FAILED} because the contract has no value for "never ran"
     * yet; that is the C1 defect, kept here deliberately so this commit stays behaviour-preserving
     * and the fix lands as a diff of its own (ADR-14 §Costs).
     */
    private static EnrollmentSignal toContractSignal(SignalState state) {
        return switch (state) {
            case SignalState.Pending ignored -> throw new IllegalStateException(
                    "PENDING signal reached the decision event mapper");
            case SignalState.Checked(var outcome) ->
                    new EnrollmentSignal(SignalOutcome.valueOf(outcome.name()), null, null);
            case SignalState.Scored(var riskLevel) ->
                    new EnrollmentSignal(null, mapRiskLevel(riskLevel), null);
            case SignalState.NoResult(var reason) ->
                    new EnrollmentSignal(null, null, reason);
            case SignalState.NotExecuted(var reason) ->
                    new EnrollmentSignal(SignalOutcome.FAILED, null, reason);
        };
    }

    private static RiskLevel mapRiskLevel(dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel riskLevel) {
        return riskLevel != null ? RiskLevel.valueOf(riskLevel.name()) : null;
    }
}
