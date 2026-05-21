package dev.sindic.enrollmenthub.decisionengine.domain;

/**
 * Risk level produced by a completed score-style signal
 * ({@link GateClassification#SCORING_SIGNAL}). Null for check-style signals.
 *
 * <p>{@code EXTREME} represents density saturation — the measurement exceeded
 * the measurable range (e.g. GEOSEARCH result-cap truncation).
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    EXTREME
}
