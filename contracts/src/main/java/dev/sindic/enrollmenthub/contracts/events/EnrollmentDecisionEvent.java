package dev.sindic.enrollmenthub.contracts.events;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Published by the decision engine after all applicable signals have settled.
 *
 * <p>{@code decisionId} is a freshly generated UUID — the internal correlation
 * {@code enrollmentId} (the DB primary key) is intentionally not exposed.
 *
 * <p>{@code originalRequest} carries the full enrollment data as submitted at
 * intake. Downstream consumers receive it as a clean nested JSON object.
 *
 * <p>{@code signals} is keyed by signal name (e.g. {@code "GEO_SCORE"},
 * {@code "FRAUD_CHECK"}).
 */
public record EnrollmentDecisionEvent(
        UUID decisionId,
        EnrollmentData originalRequest,
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
