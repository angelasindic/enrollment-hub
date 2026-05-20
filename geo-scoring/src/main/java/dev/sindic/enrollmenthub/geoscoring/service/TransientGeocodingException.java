package dev.sindic.enrollmenthub.geoscoring.service;

/**
 * Signals a transient geocoding-provider failure: 5xx responses, transport
 * errors, timeouts, or rate-limit responses. Surfaces through the AMQP
 * listener retry chain so the message is replayed; on retry exhaustion the
 * message is routed to the geo-scoring DLQ for investigation.
 *
 * <p>Distinct from an {@code Optional.empty()} return from a provider, which
 * signals the provider responded successfully with no match (ADR-010 fail-open
 * path → {@code NO_RESULT} signal).
 */
public class TransientGeocodingException extends RuntimeException {

    public TransientGeocodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
