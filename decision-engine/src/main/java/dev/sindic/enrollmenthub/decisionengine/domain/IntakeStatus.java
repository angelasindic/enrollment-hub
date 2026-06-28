package dev.sindic.enrollmenthub.decisionengine.domain;

/**
 * Intake idempotency-ledger state for a correlation record (ADR-13 §Ingress Inversion
 * &amp; Consumer-Side State Machine).
 *
 * <p>{@code PENDING}: the correlation record is committed but the per-signal check
 * commands have not been confirmed dispatched. {@code COMPLETED}: the commands have been
 * dispatched, so a redelivered intake message is acknowledged without re-dispatch.
 *
 * <p>State transition: {@code PENDING → COMPLETED}, set by the intake consumer after a
 * successful downstream dispatch and before the intake message is acknowledged.
 */
public enum IntakeStatus {
    PENDING,
    COMPLETED
}
