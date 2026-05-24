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
    private final EnrollmentCorrelationService correlationService;
    private final EnrollmentIntakePublisher intakePublisher;
    private final EnrollmentAcceptedPublisher acceptedPublisher;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final Duration timeout;

    public EnrollmentIntakeService(EnrollmentRepository repository,
                                   EnrollmentCorrelationService correlationService,
                                   EnrollmentIntakePublisher intakePublisher,
                                   EnrollmentAcceptedPublisher acceptedPublisher,
                                   JsonMapper jsonMapper,
                                   Clock clock,
                                   @Value("${decision-engine.scatter-gather.timeout}") Duration timeout) {
        this.repository = repository;
        this.correlationService = correlationService;
        this.intakePublisher = intakePublisher;
        this.acceptedPublisher = acceptedPublisher;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.timeout = timeout;
    }

    /**
     * REST entry point — publishes to {@code enrollment.intake} for broker-backed
     * durability. No DB writes here; the correlation record is created by {@code EnrollmentIntakeListener}
     * after the broker delivers the intake message to {@link #processEnrollment(Instant, EnrollmentCommand)}.
     */
    public PendingEnrollmentResponse receiveEnrollment(EnrollmentCommand command) {
        MDC.put("enrollmentId", command.enrollmentId().toString());
        try {
            Instant createdAt = clock.instant();
            var enrollmentEvent = new EnrollmentEvent(createdAt, EnrollmentMapper.toData(command));
            intakePublisher.publish(enrollmentEvent);
            return new PendingEnrollmentResponse(enrollmentEvent.enrollmentId());
        } finally {
            MDC.remove("enrollmentId");
        }
    }

    /**
     * Listener entry point. Non-transactional.
     *   <p>The transaction boundary is deliberately narrower than the listener method:
     *  {@link EnrollmentCorrelationService#saveIfAbsent} runs in its own transaction
     *  and commits before this method proceeds to the downstream publish.
     *
     * <p>Step 1 — Transactional DB write: {@code saveIfAbsent} commits the
     * correlation record before returning. If the record already exists (broker
     * redelivery), it returns {@code true} and we proceed to retry the publish.
     *
     * <p>Step 2 — Downstream publish: happens after the DB transaction has
     * committed. A failure here throws, the Spring AMQP container NACKs the intake
     * message, and the broker redelivers. The next pass hits the idempotency guard
     * in step 1 and retries step 2.
     */
    public void processEnrollment(Instant createdAt, EnrollmentCommand command) {

        MDC.put("enrollmentId", command.enrollmentId().toString());
        try {
            boolean alreadyProcessed = correlationService.saveIfAbsent(createdAt, command);
            if (alreadyProcessed) {
                log.info("Intake redelivered for enrollmentId={}; downstream publish will be retried",
                        command.enrollmentId());
            }

            // Publish after confirmed DB commit. Exception here → NACK → redelivery.
            EnrollmentData enrollmentData = EnrollmentMapper.toData(command);
            acceptedPublisher.publish(new EnrollmentEvent(createdAt, enrollmentData));

        } finally {
            MDC.remove("enrollmentId");
        }
    }
}
