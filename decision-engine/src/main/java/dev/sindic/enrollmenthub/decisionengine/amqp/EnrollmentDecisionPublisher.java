package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentDecisionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link EnrollmentDecisionEvent} to the {@code enrollment.decisions}
 * topic exchange (ADR-003 §Layer 3 — Outbound) with routing key
 * {@link AmqpConfig#DECISION_ROUTING_KEY}. The decision-engine is the sole publisher
 * on this exchange; the account-service owns the consumer queue and its binding.
 *
 * <p>Uses the channel-scoped {@code invoke + waitForConfirmsOrDie} pattern so
 * three failure modes all surface as exceptions to the caller:
 * <ul>
 *   <li><b>Nack / lost ack</b> — {@code waitForConfirmsOrDie} throws.</li>
 *   <li><b>Unroutable</b> — the broker returns the message; this method throws
 *       {@link AmqpException} after the wait.</li>
 *   <li><b>Serialization / connection errors</b> — propagate naturally.</li>
 * </ul>
 *
 * <p><b>Rollout caveat.</b> {@link AmqpConfig} sets {@code mandatory=true} on the
 * {@link RabbitTemplate}: a publish to this exchange with no queue bound for the
 * routing key will be returned by the broker and surface as an {@link AmqpException}
 * from this method. The account-service team must declare a binding on
 * {@link AmqpConfig#DECISION_EXCHANGE} for routing key
 * {@link AmqpConfig#DECISION_ROUTING_KEY} before this publisher is exercised in
 * production. The change must be coordinated; merging this without the downstream
 * binding will cause every completed enrollment to fail the publish step and
 * trigger redelivery loops until the binding lands.
 */
@Slf4j
@Component
public class EnrollmentDecisionPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutMillis;

    EnrollmentDecisionPublisher(RabbitTemplate rabbitTemplate, AmqpProperties amqpProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutMillis = amqpProperties.confirmTimeout().toMillis();
    }

    public void publish(EnrollmentDecisionEvent event) {
        var correlation = new CorrelationData(event.decisionId().toString());

        rabbitTemplate.invoke(ops -> {
            ops.convertAndSend(AmqpConfig.DECISION_EXCHANGE, AmqpConfig.DECISION_ROUTING_KEY, event, correlation);
            ops.waitForConfirmsOrDie(confirmTimeoutMillis);
            return Boolean.TRUE;
        });

        var returned = correlation.getReturned();
        if (returned != null) {
            throw new AmqpException(
                    "EnrollmentDecisionEvent unroutable: exchange=" + returned.getExchange()
                            + " routingKey=" + returned.getRoutingKey()
                            + " replyText=" + returned.getReplyText());
        }
        log.info("Published enrollmentDecisionEvent decisionId={} decision={}",
                event.decisionId(), event.decisionResult());
    }
}
