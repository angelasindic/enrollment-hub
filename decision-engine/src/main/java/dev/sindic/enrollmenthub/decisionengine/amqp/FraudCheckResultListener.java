package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.events.FraudCheckResult;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.CheckOutcome;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;
import dev.sindic.enrollmenthub.decisionengine.service.EnrollmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class FraudCheckResultListener {

    private final EnrollmentService service;

    @RabbitListener(queues = AmqpConfig.FRAUD_CHECK_RESULT_QUEUE)
    void handleFraudCheckResult(FraudCheckResult event) {
        MDC.put("enrollmentId", event.enrollmentId().toString());
        try {
            log.info("Received fraudCheckResult outcome={}", event.outcome());
            service.recordSignalResult(event.enrollmentId(), SignalConfig.FRAUD_CHECK,
                    toSignalState(event));
        } finally {
            MDC.remove("enrollmentId");
        }
    }

    /**
     * Inbound replies only. A switch rather than the geo listener's null check because
     * {@link FraudCheckResult} reports a {@link dev.sindic.enrollmenthub.contracts.events.CheckOutcome},
     * and every one of its values maps to a state. Nothing to reject: a worker has no way to report
     * "no reply arrived" — that is the published {@code SignalOutcome}'s
     * {@code NOT_EXECUTED}, which the timeout poller produces and no listener ever sees.
     */
    private static SignalState toSignalState(FraudCheckResult event) {
        return switch (event.outcome()) {
            case OK, FAILED -> new SignalState.Checked(CheckOutcome.valueOf(event.outcome().name()));
            case NO_RESULT  -> new SignalState.NoResult(event.noResultReason());
        };
    }
}
