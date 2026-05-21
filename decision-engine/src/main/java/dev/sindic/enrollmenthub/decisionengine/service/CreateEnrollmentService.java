package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentAccepted;
import dev.sindic.enrollmenthub.decisionengine.amqp.EnrollmentAcceptedPublisher;
import dev.sindic.enrollmenthub.decisionengine.domain.EnrollmentCommand;
import dev.sindic.enrollmenthub.decisionengine.domain.PendingEnrollmentResponse;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentEntity;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Service handling enrollment flow.
 *
 * <p>The {@code originalRequest} is serialised to a JSON string once at intake
 * and stored verbatim in the correlation record. The same string is forwarded
 * inline in {@code EnrollmentDecisionEvent} via {@code @JsonRawValue}, so
 * downstream consumers receive a clean nested object regardless of any future
 * changes to {@link EnrollmentData}.
 */
@Service
@Slf4j
public class CreateEnrollmentService {

    private final EnrollmentRepository repository;
    private final EnrollmentAcceptedPublisher publisher;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final Duration timeout;

    public CreateEnrollmentService(EnrollmentRepository repository,
                                   EnrollmentAcceptedPublisher publisher,
                                   JsonMapper jsonMapper,
                                   Clock clock,
                                   @Value("${decision-engine.scatter-gather.timeout}") Duration timeout) {
        this.repository = repository;
        this.publisher = publisher;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.timeout = timeout;
    }

    @Transactional(timeout = 5)
    public PendingEnrollmentResponse createEnrollment(EnrollmentCommand command) {
        UUID requestId = UUID.randomUUID();
        MDC.put("requestId", requestId.toString());
        try {
            Instant createdAt = clock.instant();
            Instant timeoutAt = createdAt.plus(timeout);

            EnrollmentData enrollmentData = EnrollmentAcceptedMapper.toData(command);
            String originalRequest = jsonMapper.writeValueAsString(enrollmentData);

            EnrollmentEntity entity = EnrollmentEntity.create(
                    requestId, command.paymentType(), originalRequest, createdAt, timeoutAt);

            repository.save(entity);
            log.info("Persisted correlation record paymentType={}", command.paymentType());

            publisher.publish(new EnrollmentAccepted(requestId, enrollmentData));

            return PendingEnrollmentResponse.accepted(requestId);
        } finally {
            MDC.remove("requestId");
        }
    }
}
