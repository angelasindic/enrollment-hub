package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentEvent;
import dev.sindic.enrollmenthub.decisionengine.amqp.CheckRequestPublisher;
import dev.sindic.enrollmenthub.decisionengine.amqp.EnrollmentIntakePublisher;
import dev.sindic.enrollmenthub.decisionengine.domain.EnrollmentCommand;
import dev.sindic.enrollmenthub.decisionengine.domain.PendingEnrollmentResponse;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Service handling enrollment flow.
 *
 * <p>The {@code originalRequest} is serialised to a JSON string once at intake
 * and stored verbatim in the correlation record. At decision time it is mapped
 * into the {@code EnrollmentSnapshot} embedded in {@code EnrollmentDecisionEvent}
 * — without the correlation {@code enrollmentId}, which is not carried on the
 * {@code EnrollmentDecisionEvent} (a fresh {@code decisionId} is published instead;
 * ADR-17 §Amendment). Internally the {@code enrollmentId} is still the correlation
 * key across the scatter-gather and is returned to the caller in the 202.
 */
@Service
@Slf4j
public class EnrollmentIntakeService {

    private final EnrollmentCorrelationService correlationService;
    private final EnrollmentIntakePublisher intakePublisher;
    private final CheckRequestPublisher checkRequestPublisher;
    private final Clock clock;

    public EnrollmentIntakeService(
            EnrollmentCorrelationService correlationService,
            EnrollmentIntakePublisher intakePublisher,
                                   CheckRequestPublisher checkRequestPublisher,
                                   Clock clock) {
        this.correlationService = correlationService;
        this.intakePublisher = intakePublisher;
        this.checkRequestPublisher = checkRequestPublisher;
        this.clock = clock;
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
     * Listener entry point. Non-transactional. Implements the consumer-side idempotency
     * ledger of ADR-13 §Ingress Inversion as a {@code PENDING → COMPLETED} state machine,
     * with each step in its own transaction so the boundary stays narrower than the
     * listener method.
     *
     * <p>Step 1 — Insert PENDING: {@link EnrollmentCorrelationService#saveIfAbsent}
     * commits the correlation record, guarded by the {@code enrollment_id} unique
     * constraint. It returns {@code true} on a fresh insert and {@code false} on a
     * broker redelivery.
     *
     * <p>Step 2 — Read the ledger on redelivery: a redelivered message already in
     * {@code COMPLETED} state is acknowledged without re-dispatching. A redelivery still
     * in {@code PENDING} state fell through a crash before the publish completed, so it
     * is retried.
     *
     * <p>Step 3 — Idempotent downstream publish: one command per applicable signal. A
     * failure here throws, the Spring AMQP container NACKs the intake message, and the
     * broker redelivers into the PENDING branch above.
     *
     * <p>Step 4 — Transition to COMPLETED: committed before the listener acknowledges
     * the intake message. A crash between the publish and this commit redelivers into the
     * PENDING branch, which re-dispatches and relies on downstream idempotency to absorb
     * the duplicate.
     */
    public void processEnrollment(Instant createdAt, EnrollmentCommand command) {

        MDC.put("enrollmentId", command.enrollmentId().toString());
        try {
            boolean inserted = correlationService.saveIfAbsent(createdAt, command);

            if (!inserted && correlationService.isIntakeCompleted(command.enrollmentId())) {
                log.info("Intake already completed for enrollmentId={}; acknowledging duplicate without re-dispatch",
                        command.enrollmentId());
                return;
            }
            if (!inserted) {
                log.info("Intake redelivered in PENDING state for enrollmentId={}; retrying command dispatch",
                        command.enrollmentId());
            }

            EnrollmentData enrollmentData = EnrollmentMapper.toData(command);
            checkRequestPublisher.dispatch(enrollmentData, SignalConfig.applicableSignals(command.paymentType()));

            correlationService.markIntakeCompleted(command.enrollmentId());

        } finally {
            MDC.remove("enrollmentId");
        }
    }
}
