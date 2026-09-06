package dev.sindic.enrollmenthub.decisionengine.domain;

/**
 * What a {@link SignalConfig} is allowed to do to the decision, and how it behaves when it never
 * answers (ADR-14). The classification is the only thing aggregation dispatches on, so adding a
 * signal never touches {@link DecisionEngine}.
 *
 * <ul>
 *   <li>{@code REQUIRED} — <b>fail-closed</b>: an unanswered signal is settled as
 *       {@link CheckOutcome#FAILED} by the ADR-15 timeout policy, which drives
 *       {@link DecisionResult#REJECTED}. Authoritative. Unused today; reserved for checks such as
 *       sanctions screening.</li>
 *   <li>{@code BEST_EFFORT} — <b>fail-open</b>: an unanswered signal contributes nothing, but an
 *       explicit {@link CheckOutcome#FAILED} drives {@link DecisionResult#REJECTED}.
 *       Authoritative.</li>
 *   <li>{@code SCORING_SIGNAL} — <b>fail-open</b>, advisory: can raise
 *       {@link DecisionResult#CONDITIONAL_APPROVED} at HIGH or EXTREME, never
 *       {@link DecisionResult#REJECTED}.</li>
 * </ul>
 *
 * <p>Note that fail-closed is enforced at the timeout transition, not by the completion predicate
 * — {@code SignalConfig.allSettled} treats every classification alike.
 *
 * <p>The classification also fixes the shape of the result a signal may report (ADR-14
 * §Classifications), which {@link #admits} makes checkable.
 */
public enum GateClassification {
    REQUIRED,
    BEST_EFFORT,
    SCORING_SIGNAL;

    /**
     * Whether a signal of this classification can be in the given state.
     *
     * <p>Check-style classifications report a {@link CheckOutcome}, score-style a
     * {@link RiskLevel}; the remaining variants say a signal produced nothing and belong to
     * either. The pairing is otherwise held only by there being one listener per signal, and a
     * mismatch fails silently rather than loudly: {@link DecisionEngine} dispatches on the
     * classification and then matches the variant that shape implies, so a state of the wrong
     * shape matches no branch and the signal contributes nothing to the decision.
     */
    public boolean admits(SignalState state) {
        return switch (state) {
            case SignalState.Checked _ -> this != SCORING_SIGNAL;
            case SignalState.Scored  _ -> this == SCORING_SIGNAL;
            case SignalState.Pending _,
                 SignalState.NoResult _,
                 SignalState.NotExecuted _ -> true;
        };
    }
}
