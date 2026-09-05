package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;

/**
 * A signal produced a state its classification cannot use — a score where a verdict was expected,
 * or the reverse. Non-retryable: the mismatch is between a {@link SignalConfig}'s classification
 * and the listener that feeds it, so every message for that signal fails the same way until one of
 * them changes.
 *
 * <p>Rejecting is the point. Left through, the state reaches aggregation, matches no branch for its
 * classification, and the signal silently contributes nothing to the decision.
 */
public class SignalShapeMismatchException extends RuntimeException {

    public SignalShapeMismatchException(SignalConfig signal, SignalState state) {
        super(signal + " is " + signal.classification() + " and cannot be "
                + state.getClass().getSimpleName());
    }
}
