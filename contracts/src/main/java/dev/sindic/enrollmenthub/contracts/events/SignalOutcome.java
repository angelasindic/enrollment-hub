package dev.sindic.enrollmenthub.contracts.events;

/**
 * A signal's outcome as published on {@link EnrollmentDecisionEvent}. {@code OK} and
 * {@code FAILED} carry a check-style verdict; {@code NOT_EXECUTED} is not a verdict but the
 * absence of one — no reply arrived before the deadline — so it applies to score-style signals
 * too. A signal that ran without producing a result publishes no outcome at all, only a reason.
 *
 * <p>Distinct from {@link CheckOutcome}, which is what a worker reports. The vocabularies differ:
 * a worker cannot conclude {@code NOT_EXECUTED}, and this event never carries {@code NO_RESULT}.
 */
public enum SignalOutcome {
    OK,
    FAILED,
    NOT_EXECUTED
}
