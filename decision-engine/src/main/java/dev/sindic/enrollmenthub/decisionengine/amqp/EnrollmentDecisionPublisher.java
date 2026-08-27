package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentDecisionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link EnrollmentDecisionEvent} to the {@code enrollment.decisions} topic exchange.
 * Sole publisher; the account service owns the consumer queue and its binding (ADR-13
 * §Channel Ownership).
 *
 * <p>Channel-scoped {@code invoke + waitForConfirmsOrDie}, so a nack, a lost ack, an unroutable
 * return, or a connection error all reach the caller as an exception. That matters more here than
 * elsewhere: {@code DecisionDispatcher} stamps {@code dispatched_at} only if this method returns
 * normally, so a swallowed failure would mark an undelivered decision as delivered (ADR-17).
 *
 * <p><b>Rollout caveat.</b> {@code mandatory=true} means a publish with no queue bound for
 * {@link AmqpConfig#DECISION_ROUTING_KEY} is returned and throws. The account-service binding must
 * land before this path is exercised in production, or every decided enrollment fails its publish
 * and falls to the relay until it does.
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
