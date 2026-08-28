package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.decisionengine.domain.IntakeStatus;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Correlation-record writes for the intake path, each in its own short transaction so the
 * listener's database boundary stays narrow (ADR-13 §Ingress Inversion): insert the record, read
 * the intake ledger, advance it to COMPLETED.
 */
@Service
@Slf4j
public class EnrollmentCorrelationService {

    private final EnrollmentRepository repository;
    private final JsonMapper jsonMapper;
    private final Duration timeout;

    public EnrollmentCorrelationService(EnrollmentRepository repository,
                                        JsonMapper jsonMapper,
                                        @Value("${decision-engine.scatter-gather.timeout}") Duration timeout) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.timeout = timeout;
    }

    /**
     * Persists the correlation record if absent, reporting whether state changed
     * ({@link java.util.Set#add} convention).
     *
     * @return {@code true} if this call inserted; {@code false} on an idempotent redelivery
     */
    //TODO hardcoded timeout
    @Transactional(timeout = 10)
    public boolean saveIfAbsent(Instant createdAt, EnrollmentData enrollmentData) {
        var enrollmentId = enrollmentData.enrollmentId();
        MDC.put("enrollmentId", enrollmentId.toString());
        try {
            Instant timeoutAt = createdAt.plus(timeout);
            var paymentType = EnrollmentMapper.toDomainPaymentType(enrollmentData.paymentType());
            String originalRequest;
            String signalsJson;
            try {
                originalRequest = jsonMapper.writeValueAsString(enrollmentData);
                signalsJson = jsonMapper.writeValueAsString(SignalConfig.initializeFor(paymentType));
            } catch (JacksonException jackExc) {
                throw new EnrollmentSerializationException(enrollmentId, jackExc);
            }

            // Atomic INSERT ... ON CONFLICT DO NOTHING: 1 row means we inserted;
            // 0 rows means a concurrent redelivery already inserted this enrollmentId.
            boolean inserted = repository.insertIfAbsent(
                    enrollmentId,
                    enrollmentData.paymentType().name(),
                    originalRequest,
                    signalsJson,
                    createdAt,
                    timeoutAt) == 1;

            if (inserted) {
                log.info("Persisted correlation record '{}' paymentType={}",
                        enrollmentId, enrollmentData.paymentType());
            } else {
                log.info("Correlation record already exists for enrollmentId={}; idempotent redelivery",
                        enrollmentId);
            }
            return inserted;
        } finally {
            MDC.remove("enrollmentId");
        }
    }

    /**
     * {@code true} only when the record exists and its commands were already dispatched — the
     * consumer's cue to acknowledge a redelivered intake message without re-dispatching.
     */
    public boolean isIntakeCompleted(UUID enrollmentId) {
        return repository.findIntakeStatus(enrollmentId)
                .map(IntakeStatus.COMPLETED::equals)
                .orElse(false);
    }

    /**
     * Advances the intake ledger to COMPLETED, committed before the intake message is
     * acknowledged. A row count other than one means the record is gone; logged rather than
     * thrown, because the dispatch this records has already succeeded.
     */
    @Transactional(timeout = 10)
    public void markIntakeCompleted(UUID enrollmentId) {
        int updated = repository.markIntakeCompleted(enrollmentId);
        if (updated != 1) {
            log.warn("markIntakeCompleted affected {} rows for enrollmentId={}", updated, enrollmentId);
        }
    }
}