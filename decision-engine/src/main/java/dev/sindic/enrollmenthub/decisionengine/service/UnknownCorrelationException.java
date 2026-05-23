package dev.sindic.enrollmenthub.decisionengine.service;

import java.util.UUID;

/**
 * Signal result arrived for a {@code enrollmentId} with no corresponding correlation
 * record. Non-retryable — re-invoking the listener will not make the row appear,
 * so the listener advice chain routes this exception type straight to the DLQ
 * via {@code RetryPolicy.excludes}.
 *
 * <p>In steady state this exception is expected to be extinct: it indicates
 * either a dual-write race at the entry point or an out-of-order publish from
 * a misconfigured producer. Either case warrants manual triage from the DLQ.
 */
public class UnknownCorrelationException extends RuntimeException {

    public UnknownCorrelationException(UUID enrollmentId) {
        super("No correlation record found for enrollmentId=" + enrollmentId);
    }
}
