package dev.sindic.enrollmenthub.contracts.events;

/**
 * Risk level produced by a completed score-style signal (SCORING_SIGNAL).
 * Null for check-style signals.
 *
 * <p>{@code EXTREME} represents density saturation — the measurement exceeded
 * the measurable range (e.g. GEOSEARCH result-cap truncation). It is treated
 * as a distinct advisory level above HIGH by the aggregation engine.
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    EXTREME
}
