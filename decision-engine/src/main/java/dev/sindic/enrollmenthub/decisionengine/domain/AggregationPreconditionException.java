package dev.sindic.enrollmenthub.decisionengine.domain;

/**
 * Thrown when the aggregation loop is triggered while a signal is still
 * {@link SignalProcessingState#PENDING}. This should never happen in normal
 * operation — the completion predicate must fire only after all signals have
 * settled or failed.
 */
public class AggregationPreconditionException extends RuntimeException {

    AggregationPreconditionException(String message) {
        super(message);
    }
}
