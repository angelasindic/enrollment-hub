package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.events.FraudCheckResult;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalOutcome;
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

    private static SignalState toSignalState(FraudCheckResult event) {
        return switch (event.outcome()) {
            case OK, FAILED -> new SignalState.Checked(SignalOutcome.valueOf(event.outcome().name()));
            case NO_RESULT  -> new SignalState.NoResult("fraud_check_no_result");
        };
    }
}
