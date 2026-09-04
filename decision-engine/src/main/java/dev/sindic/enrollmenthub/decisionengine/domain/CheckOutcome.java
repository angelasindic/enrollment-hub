package dev.sindic.enrollmenthub.decisionengine.domain;

/**
 * What a completed check-style signal found ({@link GateClassification#BEST_EFFORT} or
 * {@link GateClassification#REQUIRED}): {@code OK} passed, {@code FAILED} did not — and for a
 * {@code BEST_EFFORT} signal drives {@link DecisionResult#REJECTED}.
 *
 * <p>Narrower than the {@code contracts} enum of the same name. "Ran but reached no verdict" is
 * {@link SignalState.NoResult} here rather than a constant, because a score-style signal can end
 * that way too — geocoding failure — and because the variant carries the reason a constant cannot.
 */
public enum CheckOutcome {
    OK,
    FAILED
}
