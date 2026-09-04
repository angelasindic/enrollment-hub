package dev.sindic.enrollmenthub.contracts.events;

/**
 * What a check-style signal found: {@code OK} passed, {@code FAILED} did not pass,
 * {@code NO_RESULT} ran but could not reach a verdict. The score-style counterpart is
 * {@link RiskLevel}. What each implies for a decision is not defined here.
 */
public enum CheckOutcome {
    OK,
    FAILED,
    NO_RESULT
}
