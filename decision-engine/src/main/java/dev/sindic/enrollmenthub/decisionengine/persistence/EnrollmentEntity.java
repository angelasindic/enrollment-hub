package dev.sindic.enrollmenthub.decisionengine.persistence;

import dev.sindic.enrollmenthub.decisionengine.domain.*;
import jakarta.persistence.*;
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
 * the {@code signals} JSONB column via {@code updateSignals(...)} per ADR-015 §Write path,
 * and the scalar decision columns via {@code recordDecision(...)} for symmetry.
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
 * {@code requestId} to avoid exposing the internal primary key.
 *
 * @see EnrollmentProcess
 */
@Entity
@Table(name = "enrollments", schema = "enrollment_hub")
public class EnrollmentEntity {

    @Id
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

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

    protected EnrollmentEntity() {}

    private EnrollmentEntity(UUID requestId,
                              PaymentType paymentType,
                              String originalRequest,
                              Map<SignalConfig, SignalState> signals,
                              Instant createdAt,
                              Instant timeoutAt) {
        this.requestId = requestId;
        this.paymentType = paymentType;
        this.originalRequest = originalRequest;
        this.signals = new EnumMap<>(signals);
        this.createdAt = createdAt;
        this.timeoutAt = timeoutAt;
    }

    public static EnrollmentEntity create(UUID requestId,
                                          PaymentType paymentType,
                                          String originalRequest,
                                          Instant createdAt,
                                          Instant timeoutAt) {
        return new EnrollmentEntity(
                requestId, paymentType, originalRequest,
                SignalConfig.initializeFor(paymentType),
                createdAt, timeoutAt);
    }

    public boolean isComplete() {
        return SignalConfig.allSettled(signals);
    }

    public EnrollmentProcess toDomainForDecision() {
        return toDomain(new EnrollmentCommand(paymentType, null, null, null));
    }

    public EnrollmentProcess toDomain(EnrollmentCommand command) {
        return new EnrollmentProcess(requestId, command, signals, createdAt, timeoutAt);
    }

    // --- Getters ---

    public UUID getRequestId()               { return requestId; }
    public PaymentType getPaymentType()      { return paymentType; }
    public String getOriginalRequest()        { return originalRequest; }
    public Map<SignalConfig, SignalState> getSignals() { return signals; }
    public DecisionResult getDecisionResult(){ return decisionResult; }
    public UUID getDecisionId()              { return decisionId; }
    public Instant getCreatedAt()            { return createdAt; }
    public Instant getTimeoutAt()            { return timeoutAt; }
    public Instant getDecidedAt()            { return decidedAt; }
}
