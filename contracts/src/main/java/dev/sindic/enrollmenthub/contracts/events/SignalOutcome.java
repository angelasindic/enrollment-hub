package dev.sindic.enrollmenthub.contracts.events;

/**
 * Result of a check-style signal: {@code OK} passed, {@code FAILED} did not pass, {@code NO_RESULT}
 * ran but could not reach a verdict. What each implies for a decision is not defined here.
 */
public enum SignalOutcome {
    OK,
    FAILED,
    NO_RESULT
}
