package dev.sindic.enrollmenthub.frauddetection.amqp;

import dev.sindic.enrollmenthub.contracts.events.FraudCheckResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link FraudCheckResult} events to the {@code enrollment.check.result} direct exchange
 * with routing key {@link AmqpConfig#RESULT_ROUTING_KEY}.
 *
 * <p>Uses the channel-scoped {@code invoke + waitForConfirmsOrDie} pattern so nacks, lost acks, and
 * unroutable returns all surface as exceptions to the caller; the failure drops into the listener
 * retry interceptor. Consumers dedup by {@code enrollmentId} (Idempotent Receiver — ADR-009).
 */
@Slf4j
@Component
public class FraudCheckResultPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutMillis;

    FraudCheckResultPublisher(RabbitTemplate rabbitTemplate, AmqpProperties amqpProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutMillis = amqpProperties.confirmTimeout().toMillis();
    }

    public void publish(FraudCheckResult event) {
        var correlation = new CorrelationData(event.enrollmentId().toString());

        rabbitTemplate.invoke(ops -> {
            ops.convertAndSend(AmqpConfig.RESULT_EXCHANGE, AmqpConfig.RESULT_ROUTING_KEY, event, correlation);
            ops.waitForConfirmsOrDie(confirmTimeoutMillis);
            return Boolean.TRUE;
        });

        var returned = correlation.getReturned();
        if (returned != null) {
            throw new AmqpException(
                    "FraudCheckResult unroutable: exchange=" + returned.getExchange()
                            + " routingKey=" + returned.getRoutingKey()
                            + " replyText=" + returned.getReplyText());
        }
        log.info("Published fraudCheckResult enrollmentId={} outcome={}", event.enrollmentId(), event.outcome());
    }
}
