package dev.sindic.enrollmenthub.geoscoring.amqp;

import dev.sindic.enrollmenthub.contracts.events.GeoScoreRequest;
import dev.sindic.enrollmenthub.geoscoring.service.GeoScoringService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@link GeoScoreRequest} commands from the decision-engine-owned request queue
 * {@link AmqpConfig#REQUEST_QUEUE} (bound to {@code enrollment.check.request} on routing key
 * {@code geo.score}). The command carries only the shipping address and the correlation
 * {@code enrollmentId} — geo-scoring needs no other enrollment data.
 */
@Slf4j
@Component
class GeoScoreRequestListener {

    private final GeoScoringService geoScoringService;

    public GeoScoreRequestListener(GeoScoringService geoScoringService) {
        this.geoScoringService = geoScoringService;
    }

    @RabbitListener(queues = AmqpConfig.REQUEST_QUEUE)
    void handleGeoScoreRequest(GeoScoreRequest request) {
        MDC.put("enrollmentId", request.enrollmentId().toString());
        try {
            log.info("Received geoScoreRequest");
            geoScoringService.scoreAddress(request.enrollmentId(), request.shippingAddress());
        } finally {
            MDC.remove("enrollmentId");
        }
    }
}
