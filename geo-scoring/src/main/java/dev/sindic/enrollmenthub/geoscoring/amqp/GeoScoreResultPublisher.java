package dev.sindic.enrollmenthub.geoscoring.amqp;

import dev.sindic.enrollmenthub.contracts.events.GeoScoreResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link GeoScoreResult} events to the {@code enrollment.events}
 * topic exchange with routing key {@link AmqpConfig#RESULT_ROUTING_KEY}.
 *
 * <p>Uses the channel-scoped {@code invoke + waitForConfirmsOrDie} pattern so
 * three failure modes all surface as exceptions to the caller:
 * <ul>
 *   <li><b>Nack / lost ack</b> — {@code waitForConfirmsOrDie} throws.</li>
 *   <li><b>Unroutable</b> — the broker returns the message; this method throws
 *       {@link AmqpException} after the wait.</li>
 *   <li><b>Serialization / connection errors</b> — propagate naturally.</li>
 * </ul>
 * All three drop into the listener retry interceptor. Consumers dedup by
 * {@code enrollmentId} (Idempotent Receiver — ADR-009 §Delivery Semantics).
 */
@Slf4j
@Component
public class GeoScoreResultPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutMillis;

    GeoScoreResultPublisher(RabbitTemplate rabbitTemplate, AmqpProperties amqpProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutMillis = amqpProperties.confirmTimeout().toMillis();
    }

    public void publish(GeoScoreResult event) {
        var correlation = new CorrelationData(event.enrollmentId().toString());

        rabbitTemplate.invoke(ops -> {
            ops.convertAndSend(AmqpConfig.EXCHANGE, AmqpConfig.RESULT_ROUTING_KEY, event, correlation);
            ops.waitForConfirmsOrDie(confirmTimeoutMillis);
            return Boolean.TRUE;
        });

        var returned = correlation.getReturned();
        if (returned != null) {
            throw new AmqpException(
                    "GeoScoreResult unroutable: exchange=" + returned.getExchange()
                            + " routingKey=" + returned.getRoutingKey()
                            + " replyText=" + returned.getReplyText());
        }
        log.info("Published geoScoreResult enrollmentId={} riskLevel={}",
                event.enrollmentId(), event.riskLevel());
    }
}
