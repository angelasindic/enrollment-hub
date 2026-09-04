package dev.sindic.enrollmenthub.contracts.events;

/**
 * Whether an explanation was actually supplied. Blank counts as absent — a reason nobody can read
 * is not one. Several records make a result conditional on this, and the rule is theirs jointly.
 */
final class Reason {

    private Reason() {}

    static boolean isGiven(String reason) {
        return reason != null && !reason.isBlank();
    }
}
