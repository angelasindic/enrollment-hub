package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentEvent;
import dev.sindic.enrollmenthub.decisionengine.service.EnrollmentIntakeService;
import dev.sindic.enrollmenthub.decisionengine.service.EnrollmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@link EnrollmentEvent} from the intake queue and hands it to
 * {@link EnrollmentIntakeService#processEnrollment(java.time.Instant,
 * dev.sindic.enrollmenthub.decisionengine.domain.EnrollmentCommand)} for the
 * read-modify-write half of the broker-backed durability pattern.
 *
 * <p>The event's {@code createdAt} is preserved end-to-end so the correlation
 * row's timeout deadline is anchored on the original submission time, not on
 * the listener's processing time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EnrollmentIntakeListener {

    private final EnrollmentIntakeService service;

    @RabbitListener(queues = AmqpConfig.ENROLLMENT_INTAKE_QUEUE)
    void handleIntake(EnrollmentEvent event) {
        MDC.put("requestId", event.enrollmentId());
        try {
            log.info("Received enrollment id={}", event.enrollmentId());
            service.processEnrollment(event.createdAt(), EnrollmentMapper.toCommand(event));
        } finally {
            MDC.remove("requestId");
        }
    }
}
