package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.decisionengine.domain.EnrollmentCommand;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentEntity;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

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
    private final Clock clock;
    private final Duration timeout;

    public EnrollmentCorrelationService(EnrollmentRepository repository,
                                        JsonMapper jsonMapper,
                                        Clock clock,
                                        @Value("${decision-engine.scatter-gather.timeout}") Duration timeout) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.timeout = timeout;
    }

    /**
     * Persists the correlation record if it does not already exist.
     *
     * @return {@code true} if the record already exists (redelivery);
     *         {@code false} if a new record was inserted
     */
    //TODO hardcoded timeout
    @Transactional(timeout = 10)
    public boolean saveIfAbsent(Instant createdAt, EnrollmentCommand command) {
        MDC.put("enrollmentId", command.enrollmentId().toString());
        try {
            if (repository.existsById(command.enrollmentId())) {
                log.info("Correlation record already exists for enrollmentId={}; idempotent redelivery",
                        command.enrollmentId());
                return true;
            }

            Instant timeoutAt = createdAt.plus(timeout);
            EnrollmentData enrollmentData = EnrollmentMapper.toData(command);
            String originalRequest;
            try {
                originalRequest = jsonMapper.writeValueAsString(enrollmentData);
            } catch (JacksonException jackExc) {
                throw new EnrollmentSerializationException(command.enrollmentId(), jackExc);
            }

            EnrollmentEntity entity = EnrollmentEntity.create(
                    command.enrollmentId(),
                    command.paymentType(),
                    originalRequest,
                    createdAt,
                    timeoutAt
            );
            repository.save(entity);
            log.info("Persisted correlation record '{}' paymentType={}",
                    command.enrollmentId(), command.paymentType());
            return false;
        } finally {
            MDC.remove("enrollmentId");
        }
    }
}