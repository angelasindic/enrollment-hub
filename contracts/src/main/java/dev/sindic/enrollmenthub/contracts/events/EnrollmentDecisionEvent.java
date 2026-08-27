package dev.sindic.enrollmenthub.contracts.events;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentSnapshot;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The enrollment decision. Owned by the decision-engine.
 *
 * <p>Delivery is at-least-once: {@code decisionId} is stable across redeliveries of the same
 * decision, so consumers deduplicate on it (ADR-17). {@code signals} is keyed by signal name, e.g.
 * {@code "GEO_SCORE"}.
 */
public record EnrollmentDecisionEvent(
        UUID decisionId,
        EnrollmentSnapshot originalRequest,
        DecisionResult decisionResult,
        Map<String, EnrollmentSignal> signals,
        Instant decidedAt
) {
    public EnrollmentDecisionEvent {
        Objects.requireNonNull(decisionId, "decisionId must not be null");
        Objects.requireNonNull(originalRequest, "originalRequest must not be null");
        Objects.requireNonNull(decisionResult, "decisionResult must not be null");
        Objects.requireNonNull(signals, "signals must not be null");
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        signals = Map.copyOf(signals);
    }
}
