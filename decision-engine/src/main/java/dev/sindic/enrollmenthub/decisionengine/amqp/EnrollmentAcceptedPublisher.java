package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentAccepted;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link EnrollmentAccepted} events to the {@code enrollment.events}
 * topic exchange, routing by payment type (see {@link AmqpConfig#routingKeyFor}).
 *
 * <p>Uses the channel-scoped {@code invoke + waitForConfirmsOrDie} pattern so
 * three failure modes all surface as exceptions to the caller:
 * <ul>
 *   <li><b>Nack / lost ack</b> — {@code waitForConfirmsOrDie} throws.</li>
 *   <li><b>Unroutable</b> (bad exchange / routing key / missing binding) — the
 *       broker returns the message; the return is attached to the per-publish
 *       {@link CorrelationData} before the ack and this method throws
 *       {@link AmqpException} after the wait.</li>
 *   <li><b>Serialization / connection errors</b> — propagate naturally.</li>
 * </ul>
 * Consumers dedup by {@code requestId} (Idempotent Receiver — ADR-009 §Delivery Semantics).
 */
@Slf4j
@Component
public class EnrollmentAcceptedPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutMillis;

    EnrollmentAcceptedPublisher(RabbitTemplate rabbitTemplate, AmqpProperties amqpProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutMillis = amqpProperties.confirmTimeout().toMillis();
    }

    public void publish(EnrollmentAccepted event) {
        var routingKey = AmqpConfig.routingKeyFor(event.enrollmentData().paymentType());
        var correlation = new CorrelationData(event.requestId().toString());

        rabbitTemplate.invoke(ops -> {
            ops.convertAndSend(AmqpConfig.EXCHANGE, routingKey, event, correlation);
            ops.waitForConfirmsOrDie(confirmTimeoutMillis);
            return Boolean.TRUE;
        });

        var returned = correlation.getReturned();
        if (returned != null) {
            throw new AmqpException(
                    "EnrollmentAccepted unroutable: exchange=" + returned.getExchange()
                            + " routingKey=" + returned.getRoutingKey()
                            + " replyText=" + returned.getReplyText());
        }
        log.info("Published enrollmentAccepted requestId={} routingKey={}",
                event.requestId(), routingKey);
    }
}
