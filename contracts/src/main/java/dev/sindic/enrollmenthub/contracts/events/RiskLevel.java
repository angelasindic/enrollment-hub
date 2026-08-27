package dev.sindic.enrollmenthub.contracts.events;

/**
 * Result of a score-style signal, ordered least to most severe. {@code EXTREME} means the
 * measurement exceeded the measurable range rather than merely scoring high.
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    EXTREME
}
