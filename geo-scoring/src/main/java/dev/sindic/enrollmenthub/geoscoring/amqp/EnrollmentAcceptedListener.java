package dev.sindic.enrollmenthub.geoscoring.amqp;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentAccepted;
import dev.sindic.enrollmenthub.geoscoring.service.GeoScoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class EnrollmentAcceptedListener {

    private final GeoScoringService geoScoringService;

    @RabbitListener(queues = AmqpConfig.QUEUE)
    void handleEnrollmentAccepted(EnrollmentAccepted event) {
        MDC.put("requestId", event.requestId().toString());
        try {
            log.info("Received enrollmentAccepted paymentType={}",
                    event.enrollmentData().paymentType());
            geoScoringService.scoreAddress(
                    event.requestId(), event.enrollmentData().shippingAddress());
        } finally {
            MDC.remove("requestId");
        }
    }
}
