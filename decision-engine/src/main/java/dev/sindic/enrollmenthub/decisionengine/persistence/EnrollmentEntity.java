package dev.sindic.enrollmenthub.decisionengine.persistence;

import dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult;
import dev.sindic.enrollmenthub.decisionengine.domain.IntakeStatus;
import dev.sindic.enrollmenthub.decisionengine.domain.PaymentType;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * JPA entity for the {@code enrollment_hub.enrollments} correlation table — a read projection
 * only. Every write is an explicit statement on {@link EnrollmentRepository} (ADR-16 §Write path),
 * and a loaded instance is deliberately not re-read afterwards: the new state was the input to
 * that {@code UPDATE}, not something to fetch back.
 *
 * <p>Two columns exist to serve the ADR-17 outbox rather than the decision itself:
 * {@code originalRequest}, written at intake because the engine has no other source for the
 * payload the decision event must carry — the intake message is long acked, and ADR-02 rules out
 * asking the Account Service — and {@code decisionId}, generated at decide time and published in
 * place of the {@code enrollmentId} primary key.
 *
 * <p>The two have opposite lifetimes. {@code decisionId} is the frozen decision of record and
 * outlives delivery; {@code originalRequest} is an ingredient of the outbound event and has no
 * reader once {@code dispatchedAt} is stamped, so the retention job nulls it (ADR-20). A row with
 * {@code dispatchedAt IS NULL} therefore always carries its payload, which is what the outbox
 * replay depends on.
 */
@Entity
@Table(name = "enrollments", schema = "enrollment_hub")
public class EnrollmentEntity {

    @Id
    @Column(name = "enrollment_id", nullable = false, updatable = false)
    private UUID enrollmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false, updatable = false)
    private PaymentType paymentType;

    // Nullable and updatable for exactly one write: the retention strip (ADR-20). Nothing else
    // updates it — the value is set by the intake INSERT and never revised.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "original_request", columnDefinition = "jsonb")
    private String originalRequest;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "signals", nullable = false, columnDefinition = "jsonb")
    private Map<SignalConfig, SignalState> signals;

    @Enumerated(EnumType.STRING)
    @Column(name = "intake_status", nullable = false)
    private IntakeStatus intakeStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_result")
    private DecisionResult decisionResult;

    @Column(name = "decision_id")
    private UUID decisionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "timeout_at", nullable = false, updatable = false)
    private Instant timeoutAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    protected EnrollmentEntity() {}

    private EnrollmentEntity(UUID enrollmentId,
                              PaymentType paymentType,
                              String originalRequest,
                              Map<SignalConfig, SignalState> signals,
                              Instant createdAt,
                              Instant timeoutAt) {
        this.enrollmentId = enrollmentId;
        this.paymentType = paymentType;
        this.originalRequest = originalRequest;
        this.signals = new EnumMap<>(signals);
        this.intakeStatus = IntakeStatus.PENDING;
        this.createdAt = createdAt;
        this.timeoutAt = timeoutAt;
    }

    public static EnrollmentEntity create(UUID enrollmentId,
                                          PaymentType paymentType,
                                          String originalRequest,
                                          Instant createdAt,
                                          Instant timeoutAt) {
        return new EnrollmentEntity(
                enrollmentId, paymentType, originalRequest,
                SignalConfig.initializeFor(paymentType),
                createdAt, timeoutAt);
    }

    // --- Getters ---

    public UUID getEnrollmentId()            { return enrollmentId; }
    public PaymentType getPaymentType()      { return paymentType; }
    public String getOriginalRequest()        { return originalRequest; }
    public Map<SignalConfig, SignalState> getSignals() { return signals; }
    public IntakeStatus getIntakeStatus()    { return intakeStatus; }
    public DecisionResult getDecisionResult(){ return decisionResult; }
    public UUID getDecisionId()              { return decisionId; }
    public Instant getCreatedAt()            { return createdAt; }
    public Instant getTimeoutAt()            { return timeoutAt; }
    public Instant getDecidedAt()            { return decidedAt; }
    public Instant getDispatchedAt()         { return dispatchedAt; }
}
