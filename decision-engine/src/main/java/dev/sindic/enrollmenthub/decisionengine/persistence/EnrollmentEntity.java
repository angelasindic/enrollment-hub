package dev.sindic.enrollmenthub.decisionengine.persistence;

import dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult;
import dev.sindic.enrollmenthub.decisionengine.domain.EnrollmentCommand;
import dev.sindic.enrollmenthub.decisionengine.domain.EnrollmentProcess;
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
 * JPA entity for the {@code enrollment_hub.enrollments} correlation table.
 *
 * <p>Read-only projection. All writes go through {@link EnrollmentRepository}:
 * partial transitions through {@code updateSignals(...)} (signals-only UPDATE)
 * and the terminal transition through {@code completeWithDecision(...)}
 * (signals + decision columns in one UPDATE) per ADR-16 §Write path.
 * The in-memory copy of an entity that the service path loaded is intentionally
 * not re-read after those updates — the new state is the application's input
 * to the {@code UPDATE}, not a value to be re-fetched.
 *
 * <p>{@code originalRequest} stores the full enrollment data submitted at intake;
 * it is persisted so the decision event can carry it to downstream consumers
 * without a separate lookup. This column is never mutated post-INSERT.
 *
 * <p>{@code decisionId} is a freshly generated UUID set when the decision is
 * recorded. It is published in {@code EnrollmentDecisionEvent} instead of
 * {@code enrollmentId} to avoid exposing the internal primary key.
 *
 * @see EnrollmentProcess
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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "original_request", nullable = false, updatable = false, columnDefinition = "jsonb")
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

    public boolean isComplete() {
        return SignalConfig.allSettled(signals);
    }

    public EnrollmentProcess toDomainForDecision() {
        // this has to be refactored!!!!!
        return toDomain(new EnrollmentCommand(UUID.randomUUID(), paymentType, null, null, null));
    }

    public EnrollmentProcess toDomain(EnrollmentCommand command) {
        return new EnrollmentProcess(enrollmentId, command, signals, createdAt, timeoutAt);
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
