package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentEvent;
import dev.sindic.enrollmenthub.decisionengine.amqp.EnrollmentAcceptedPublisher;
import dev.sindic.enrollmenthub.decisionengine.amqp.EnrollmentIntakePublisher;
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
public class EnrollmentIntakeService {

    private final EnrollmentRepository repository;
    private final EnrollmentIntakePublisher intakePublisher;
    private final EnrollmentAcceptedPublisher acceptedPublisher;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final Duration timeout;

    public EnrollmentIntakeService(EnrollmentRepository repository,
                                   EnrollmentIntakePublisher intakePublisher,
                                   EnrollmentAcceptedPublisher acceptedPublisher,
                                   JsonMapper jsonMapper,
                                   Clock clock,
                                   @Value("${decision-engine.scatter-gather.timeout}") Duration timeout) {
        this.repository = repository;
        this.intakePublisher = intakePublisher;
        this.acceptedPublisher = acceptedPublisher;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.timeout = timeout;
    }

    /**
     * REST entry point — publishes to {@code enrollment.intake} for broker-backed
     * durability (ADR-003 §Layer 1). No DB writes here; the correlation record is
     * created by {@code EnrollmentIntakeListener} after the broker delivers the
     * intake message to {@link #processEnrollment(Instant, EnrollmentCommand)}.
     */
    public PendingEnrollmentResponse receiveEnrollment(EnrollmentCommand command) {
        MDC.put("requestId", command.enrollmentId().toString());
        try {
            Instant createdAt = clock.instant();
            var enrollmentEvent = new EnrollmentEvent(createdAt, EnrollmentMapper.toData(command));
            intakePublisher.publish(enrollmentEvent);
            return new PendingEnrollmentResponse(enrollmentEvent.enrollmentId());
        } finally {
            MDC.remove("requestId");
        }
    }

    //TODO hardcoded timeout
    //@Transactional(timeout = 5)
    @Transactional(timeout = 50)
    public void processEnrollment(Instant createdAt, EnrollmentCommand command) {

        MDC.put("requestId", command.enrollmentId().toString());
        try {
            // Idempotency gate (ADR-003 §Step 0): redelivered intake messages
            // (typically after a listener crash between save and ACK) hit a row
            // that already exists. Return without re-saving or re-publishing —
            // the original delivery's downstream consumers will dedup the event
            // they already received, so we don't re-emit it either. The PK
            // constraint on enrollmentId is the safety net behind this check.
            if (repository.existsById(command.enrollmentId())) {
                log.info("Intake already processed enrollmentId={}; ACKing redelivered message",
                        command.enrollmentId());
                return;
            }

            Instant timeoutAt = createdAt.plus(timeout);
            EnrollmentData enrollmentData = EnrollmentMapper.toData(command);
            String originalRequest = jsonMapper.writeValueAsString(enrollmentData);
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
            acceptedPublisher.publish(new EnrollmentEvent(createdAt, enrollmentData));
        } finally {
            MDC.remove("requestId");
        }
    }
}
