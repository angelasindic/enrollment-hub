package dev.sindic.enrollmenthub.decisionengine.domain;

/**
 * Thrown when {@link DecisionEngine#evaluate} is called with a signal still
 * {@link SignalProcessingState#PENDING} — the caller's completion predicate fired early. An
 * {@link IllegalStateException}, because that is what it is, and callers that catch the general
 * type keep working.
 */
public class AggregationPreconditionException extends IllegalStateException {

    AggregationPreconditionException(String message) {
        super(message);
    }
}
