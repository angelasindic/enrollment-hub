package dev.sindic.enrollmenthub.contracts.events;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentSnapshot;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Published by the decision engine after all applicable signals have settled.
 *
 * <p>{@code decisionId} is generated once and frozen when the decision is persisted (ADR-17);
 * redeliveries carry the same id, so it is the consumer-side idempotency key. The internal
 * correlation {@code enrollmentId} (the DB primary key) is intentionally not exposed —
 * {@code originalRequest} is an {@link EnrollmentSnapshot}, not the id-carrying intake payload.
 *
 * <p>{@code signals} is keyed by signal name (e.g. {@code "GEO_SCORE"},
 * {@code "FRAUD_CHECK"}).
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
