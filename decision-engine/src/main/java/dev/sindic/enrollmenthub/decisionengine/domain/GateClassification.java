package dev.sindic.enrollmenthub.decisionengine.domain;

/**
 * Classifies a {@link SignalConfig} by its role in the scatter-gather pipeline.
 *
 * <ul>
 *   <li>{@code REQUIRED} — fail-closed: blocks the completion predicate when the
 *       signal has not settled. Authoritative — can drive any {@link DecisionResult}.
 *       No current {@link SignalConfig} uses this classification; reserved for
 *       future signals such as sanctions screening or regulated KYC verification.</li>
 *   <li>{@code BEST_EFFORT} — fail-open: aggregation proceeds if the signal does
 *       not settle. Authoritative — an explicit {@link SignalOutcome#FAILED} drives
 *       {@link DecisionResult#REJECTED}.</li>
 *   <li>{@code SCORING_SIGNAL} — fail-open: advisory only. Can flag for
 *       {@link DecisionResult#CONDITIONAL_APPROVED} at HIGH or EXTREME risk level,
 *       but cannot drive {@link DecisionResult#REJECTED}.</li>
 * </ul>
 */
public enum GateClassification {
    REQUIRED,
    BEST_EFFORT,
    SCORING_SIGNAL
}
