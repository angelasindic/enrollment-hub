package dev.sindic.enrollmenthub.decisionengine.domain;

/**
 * What a {@link SignalConfig} is allowed to do to the decision, and how it behaves when it never
 * answers (ADR-14). The classification is the only thing aggregation dispatches on, so adding a
 * signal never touches {@link DecisionEngine}.
 *
 * <ul>
 *   <li>{@code REQUIRED} — <b>fail-closed</b>: an unanswered signal is settled as
 *       {@link SignalOutcome#FAILED} by the ADR-15 timeout policy, which drives
 *       {@link DecisionResult#REJECTED}. Authoritative. Unused today; reserved for checks such as
 *       sanctions screening.</li>
 *   <li>{@code BEST_EFFORT} — <b>fail-open</b>: an unanswered signal contributes nothing, but an
 *       explicit {@link SignalOutcome#FAILED} drives {@link DecisionResult#REJECTED}.
 *       Authoritative.</li>
 *   <li>{@code SCORING_SIGNAL} — <b>fail-open</b>, advisory: can raise
 *       {@link DecisionResult#CONDITIONAL_APPROVED} at HIGH or EXTREME, never
 *       {@link DecisionResult#REJECTED}.</li>
 * </ul>
 *
 * <p>Note that fail-closed is enforced at the timeout transition, not by the completion predicate
 * — {@code SignalConfig.allSettled} treats every classification alike.
 */
public enum GateClassification {
    REQUIRED,
    BEST_EFFORT,
    SCORING_SIGNAL
}
