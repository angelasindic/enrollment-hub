package dev.sindic.enrollmenthub.decisionengine.domain;

/**
 * Processing lifecycle of a single signal within the scatter-gather pipeline.
 *
 * <p>State transitions: {@code PENDING → SETTLED | FAILED}.
 * <ul>
 *   <li>{@code SETTLED} — the signal service responded; a result is available.</li>
 *   <li>{@code FAILED}  — the service did not respond (timeout or crash).
 *       Distinct from {@link SignalOutcome#NO_RESULT}, which means the service
 *       ran but explicitly could not produce a result.</li>
 * </ul>
 */
public enum SignalProcessingState {
    PENDING,
    SETTLED,
    FAILED
}
