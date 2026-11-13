package dev.sindic.enrollmenthub.accountservice.amqp;

import dev.sindic.enrollmenthub.accountservice.service.AccountProvisioningService;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentDecisionEvent;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@link EnrollmentDecisionEvent}s from {@link AmqpConfig#DECISION_QUEUE}, bound to the
 * {@code enrollment.decisions} topic exchange on {@code enrollment.decision.completed}.
 */
@Component
class EnrollmentDecisionListener {

    private final AccountProvisioningService service;

    EnrollmentDecisionListener(AccountProvisioningService service) {
        this.service = service;
    }

    @RabbitListener(queues = AmqpConfig.DECISION_QUEUE)
    void handleDecision(EnrollmentDecisionEvent event) {
        MDC.put("decisionId", event.decisionId().toString());
        try {
            service.onDecision(event);
        } finally {
            MDC.remove("decisionId");
        }
    }
}
