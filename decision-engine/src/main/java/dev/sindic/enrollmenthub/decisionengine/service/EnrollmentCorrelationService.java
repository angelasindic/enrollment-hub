package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.decisionengine.domain.EnrollmentCommand;
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
 * Idempotent correlation-record persistence.
 *
 * Insert a new correlation record and commit, or detect a duplicate and return silently.
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
     * Persists the correlation record if it does not already exist. Returns whether
     * this call performed the insert, following the {@link java.util.Set#add} convention
     * (the boolean reports whether state changed).
     *
     * @return {@code true} if a new record was inserted;
     *         {@code false} if the record already existed (idempotent redelivery)
     */
    //TODO hardcoded timeout
    @Transactional(timeout = 10)
    public boolean saveIfAbsent(Instant createdAt, EnrollmentCommand command) {
        MDC.put("enrollmentId", command.enrollmentId().toString());
        try {
            Instant timeoutAt = createdAt.plus(timeout);
            EnrollmentData enrollmentData = EnrollmentMapper.toData(command);
            String originalRequest;
            String signalsJson;
            try {
                originalRequest = jsonMapper.writeValueAsString(enrollmentData);
                signalsJson = jsonMapper.writeValueAsString(SignalConfig.initializeFor(command.paymentType()));
            } catch (JacksonException jackExc) {
                throw new EnrollmentSerializationException(command.enrollmentId(), jackExc);
            }

            // Atomic INSERT ... ON CONFLICT DO NOTHING: 1 row means we inserted;
            // 0 rows means a concurrent redelivery already inserted this enrollmentId.
            boolean inserted = repository.insertIfAbsent(
                    command.enrollmentId(),
                    command.paymentType().name(),
                    originalRequest,
                    signalsJson,
                    createdAt,
                    timeoutAt) == 1;

            if (inserted) {
                log.info("Persisted correlation record '{}' paymentType={}",
                        command.enrollmentId(), command.paymentType());
            } else {
                log.info("Correlation record already exists for enrollmentId={}; idempotent redelivery",
                        command.enrollmentId());
            }
            return inserted;
        } finally {
            MDC.remove("enrollmentId");
        }
    }

    /**
     * Reads the intake ledger state (ADR-13 §Ingress Inversion). Returns {@code true}
     * only when the record exists and its commands have already been dispatched, the
     * signal the consumer uses to acknowledge a redelivered intake message without
     * re-dispatching.
     */
    public boolean isIntakeCompleted(UUID enrollmentId) {
        return repository.findIntakeStatus(enrollmentId)
                .map(IntakeStatus.COMPLETED::equals)
                .orElse(false);
    }

    /**
     * Transitions the intake ledger {@code PENDING → COMPLETED} in its own transaction,
     * committed before the intake message is acknowledged. A row count other than one
     * means the correlation record was not found, which is logged rather than thrown
     * because the dispatch it records has already succeeded.
     */
    @Transactional(timeout = 10)
    public void markIntakeCompleted(UUID enrollmentId) {
        int updated = repository.markIntakeCompleted(enrollmentId);
        if (updated != 1) {
            log.warn("markIntakeCompleted affected {} rows for enrollmentId={}", updated, enrollmentId);
        }
    }
}