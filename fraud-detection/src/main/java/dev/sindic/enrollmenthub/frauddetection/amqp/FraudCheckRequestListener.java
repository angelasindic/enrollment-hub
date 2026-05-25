package dev.sindic.enrollmenthub.frauddetection.amqp;

import dev.sindic.enrollmenthub.contracts.events.FraudCheckRequest;
import dev.sindic.enrollmenthub.frauddetection.service.FraudDetectionService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@link FraudCheckRequest} commands from the decision-engine-owned request queue
 * {@link AmqpConfig#REQUEST_QUEUE} (bound to {@code enrollment.check.request} on routing key
 * {@code fraud.check}). Fraud detection runs on both payment routes and receives the full
 * enrollment data.
 */
@Slf4j
@Component
class FraudCheckRequestListener {

    private final FraudDetectionService service;

    public FraudCheckRequestListener(FraudDetectionService service) {
        this.service = service;
    }

    @RabbitListener(queues = AmqpConfig.REQUEST_QUEUE)
    void handleFraudCheckRequest(FraudCheckRequest request) {
        MDC.put("enrollmentId", request.enrollmentId().toString());
        try {
            log.info("Received fraudCheckRequest");
            service.check(request.enrollmentData());
        } finally {
            MDC.remove("enrollmentId");
        }
    }
}
