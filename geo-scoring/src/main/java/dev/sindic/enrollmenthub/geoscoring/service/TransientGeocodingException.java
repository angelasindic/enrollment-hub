package dev.sindic.enrollmenthub.geoscoring.service;

/**
 * Signals a transient geocoding-provider failure: 5xx responses, transport
 * errors, timeouts, or rate-limit responses. Surfaces through the AMQP
 * listener retry chain so the message is replayed; on retry exhaustion the
 * message is routed to the geo-scoring DLQ for investigation.
 *
 * <p>Distinct from an {@code Optional.empty()} provider return, which means the provider answered
 * but found no match. That publishes a {@code GeoScoreResult} with a null {@code riskLevel}, which
 * the decision-engine settles without a result — advisory signals never block a decision
 * (ADR-14).
 */
public class TransientGeocodingException extends RuntimeException {

    public TransientGeocodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
