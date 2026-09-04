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

    @RabbitListener(queues = AmqpConfig.GEO_SCORE_RESULT_QUEUE)
    void handleGeoScoreResult(GeoScoreResult event) {
        MDC.put("enrollmentId", event.enrollmentId().toString());
        try {
            log.info("Received geoScoreResult riskLevel={}", event.riskLevel());
            service.recordSignalResult(event.enrollmentId(), SignalConfig.GEO_SCORE, toSignalState(event));
        } finally {
            MDC.remove("enrollmentId");
        }
    }

    /**
     * Inbound replies only. A timed-out GEO_SCORE still publishes {@code NOT_EXECUTED}, the same as
     * fraud — that state comes from the timeout poller, never from a message, so no listener sees
     * it. Nothing to reject here either: {@link GeoScoreResult} carries no
     * {@link dev.sindic.enrollmenthub.contracts.events.SignalOutcome}, so unlike
     * {@code FraudCheckResult} it has no field the value could arrive in. The contradiction geo
     * <em>can</em> send — a contradiction between its two result fields — the record rejects itself:
     * exactly one of {@code riskLevel} and {@code noResultReason} is set, so the two branches below
     * are total and neither can be reached by a malformed reply.
     */
    private static SignalState toSignalState(GeoScoreResult event) {
        if (event.riskLevel() == null) {
            return new SignalState.NoResult(event.noResultReason());
        }
        return new SignalState.Scored(RiskLevel.valueOf(event.riskLevel().name()));
    }
}
