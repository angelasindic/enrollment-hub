package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.events.GeoScoreResult;
import dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
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
class GeoScoreResultListener {

    private final EnrollmentService service;

    @RabbitListener(queues = AmqpConfig.GEO_SCORE_QUEUE)
    void handleGeoScoreResult(GeoScoreResult event) {
        MDC.put("requestId", event.requestId().toString());
        try {
            log.info("Received geoScoreResult riskLevel={}", event.riskLevel());
            service.recordSignalResult(event.requestId(), SignalConfig.GEO_SCORE, toSignalState(event));
        } finally {
            MDC.remove("requestId");
        }
    }

    private static SignalState toSignalState(GeoScoreResult event) {
        if (event.riskLevel() == null) {
            return SignalState.settledWithoutResult(event.noResultReason());
        }
        return SignalState.settled(RiskLevel.valueOf(event.riskLevel().name()));
    }
}
