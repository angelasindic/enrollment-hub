package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.events.FraudCheckRequest;
import dev.sindic.enrollmenthub.contracts.events.GeoScoreRequest;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.UUID;

/**
 * Dispatches per-signal check commands to the {@link AmqpConfig#CHECK_REQUEST_EXCHANGE} direct
 * exchange, routed by signal name (ADR-003 §Layer 2). One command is published per signal
 * applicable to the route; the applicable set is supplied by the caller from {@link SignalConfig}
 * — the same source that seeds the correlation record's gather-set, so dispatch and gather cannot
 * drift. Each command carries a least-privilege payload (geo-scoring: shipping address only; fraud
 * detection: the full enrollment data).
 *
 * <p>Each publish uses the channel-scoped {@code invoke + waitForConfirmsOrDie} pattern so nacks,
 * lost acks, and unroutable returns all surface as exceptions; the caller's failure propagates to
 * the intake listener, which NACKs and lets the broker redeliver (idempotent receivers downstream
 * absorb the duplicate). Publishes are independent — a partial dispatch is recovered by redelivery.
 */
@Slf4j
@Component
public class CheckRequestPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutMillis;

    CheckRequestPublisher(RabbitTemplate rabbitTemplate, AmqpProperties amqpProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutMillis = amqpProperties.confirmTimeout().toMillis();
    }

    public void dispatch(EnrollmentData data, Collection<SignalConfig> applicableSignals) {
        for (var signal : applicableSignals) {
            switch (signal) {
                case GEO_SCORE -> publish(AmqpConfig.GEO_SCORE_KEY,
                        new GeoScoreRequest(data.enrollmentId(), data.shippingAddress()), data.enrollmentId());
                case FRAUD_CHECK -> publish(AmqpConfig.FRAUD_CHECK_KEY,
                        new FraudCheckRequest(data), data.enrollmentId());
            }
        }
    }

    private void publish(String routingKey, Object command, UUID enrollmentId) {
        var correlation = new CorrelationData(enrollmentId + ":" + routingKey);

        rabbitTemplate.invoke(ops -> {
            ops.convertAndSend(AmqpConfig.CHECK_REQUEST_EXCHANGE, routingKey, command, correlation);
            ops.waitForConfirmsOrDie(confirmTimeoutMillis);
            return Boolean.TRUE;
        });

        var returned = correlation.getReturned();
        if (returned != null) {
            throw new AmqpException(
                    "Check request unroutable: exchange=" + returned.getExchange()
                            + " routingKey=" + returned.getRoutingKey()
                            + " replyText=" + returned.getReplyText());
        }
        log.info("Dispatched check request enrollmentId={} routingKey={}", enrollmentId, routingKey);
    }
}
