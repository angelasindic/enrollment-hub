package dev.sindic.enrollmenthub.contracts.events;

/**
 * Outcome produced by a completed check-style signal (BEST_EFFORT or REQUIRED).
 * Null for score-style signals.
 *
 * <ul>
 *   <li>{@code OK}        — verification passed.</li>
 *   <li>{@code FAILED}    — verification did not pass; for BEST_EFFORT signals this drives REJECTED.</li>
 *   <li>{@code NO_RESULT} — signal ran but could not produce a meaningful result (fail-open).</li>
 * </ul>
 */
public enum SignalOutcome {
    OK,
    FAILED,
    NO_RESULT
}
