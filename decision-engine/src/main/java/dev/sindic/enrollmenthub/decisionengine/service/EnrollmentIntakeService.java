package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.decisionengine.amqp.EnrollmentEvent;
import dev.sindic.enrollmenthub.decisionengine.amqp.CheckRequestPublisher;
import dev.sindic.enrollmenthub.decisionengine.amqp.EnrollmentIntakePublisher;
import dev.sindic.enrollmenthub.decisionengine.domain.EnrollmentCommand;
import dev.sindic.enrollmenthub.decisionengine.domain.PendingEnrollmentResponse;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Scatter half of the scatter-gather pipeline — accepts an enrollment and dispatches one
 * command per applicable signal.
 *
 * <p>Two entry points, deliberately split by ADR-13 §Ingress Inversion:
 * {@link #receiveEnrollment} answers the REST caller and does nothing but publish, so intake
 * durability is the broker's; {@link #processEnrollment} runs off that message and owns the
 * database work.
 *
 * <p>{@code enrollmentId} is the correlation key across the scatter-gather and is returned to
 * the caller in the 202, but it is not carried on {@code EnrollmentDecisionEvent} — a fresh
 * {@code decisionId} is published instead (ADR-17 §Amendment).
 *
 * @see EnrollmentService gather half — signal results, decision, emission
 */
@Service
@Slf4j
public class EnrollmentIntakeService {

    private final EnrollmentCorrelationService correlationService;
    private final EnrollmentIntakePublisher intakePublisher;
    private final CheckRequestPublisher checkRequestPublisher;
    private final Clock clock;

    public EnrollmentIntakeService(EnrollmentCorrelationService correlationService,
                                   EnrollmentIntakePublisher intakePublisher,
                                   CheckRequestPublisher checkRequestPublisher,
                                   Clock clock) {
        this.correlationService = correlationService;
        this.intakePublisher = intakePublisher;
        this.checkRequestPublisher = checkRequestPublisher;
        this.clock = clock;
    }

    /**
     * REST entry point. Publishes to {@code enrollment.intake} and returns; no database write
     * happens here, the correlation record is created once the broker delivers that message to
     * {@link #processEnrollment}.
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
     * Listener entry point, implementing the consumer-side idempotency ledger of
     * ADR-13 §Ingress Inversion as a {@code PENDING → COMPLETED} state machine: insert the row,
     * dispatch one command per applicable signal, then mark COMPLETED.
     *
     * <p>Deliberately non-transactional, with each step committing separately, so the database
     * boundary stays narrower than the listener method and the ledger records real progress
     * rather than rolling back with it. Every crash window lands somewhere recoverable — a
     * redelivery finds COMPLETED and acknowledges without re-dispatching, or finds PENDING and
     * re-dispatches, relying on downstream idempotency to absorb the duplicate. A publish failure
     * throws, the container NACKs, and the broker redelivers into that same PENDING branch.
     */
    public void processEnrollment(Instant createdAt, EnrollmentData enrollmentData) {

        var enrollmentId = enrollmentData.enrollmentId();
        MDC.put("enrollmentId", enrollmentId.toString());
        try {
            boolean inserted = correlationService.saveIfAbsent(createdAt, enrollmentData);

            if (!inserted && correlationService.isIntakeCompleted(enrollmentId)) {
                log.info("Intake already completed for enrollmentId={}; acknowledging duplicate without re-dispatch",
                        enrollmentId);
                return;
            }
            if (!inserted) {
                log.info("Intake redelivered in PENDING state for enrollmentId={}; retrying command dispatch",
                        enrollmentId);
            }

            var paymentType = EnrollmentMapper.toDomainPaymentType(enrollmentData.paymentType());
            checkRequestPublisher.dispatch(enrollmentData, SignalConfig.applicableSignals(paymentType));

            correlationService.markIntakeCompleted(enrollmentId);

        } finally {
            MDC.remove("enrollmentId");
        }
    }
}
