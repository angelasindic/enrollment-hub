package dev.sindic.enrollmenthub.geoscoring.amqp;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentEvent;
import dev.sindic.enrollmenthub.geoscoring.service.GeoScoringService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
class EnrollmentAcceptedListener {

    private final GeoScoringService geoScoringService;

    public EnrollmentAcceptedListener(GeoScoringService geoScoringService) {
        this.geoScoringService = geoScoringService;
    }

    @RabbitListener(queues = AmqpConfig.QUEUE)
    void handleEnrollmentAccepted(EnrollmentEvent event) {
        MDC.put("enrollmentId", event.enrollmentId());
        try {
            log.info("Received enrollmentAccepted paymentType={}",
                    event.enrollmentData().paymentType());
            geoScoringService.scoreAddress(
                    event.enrollmentData().enrollmentId(), event.enrollmentData().shippingAddress());
        } finally {
            MDC.remove("enrollmentId");
        }
    }
}
