package dev.sindic.enrollmenthub.decisionengine.amqp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link EnrollmentEvent} to the {@code enrollment.intake} direct exchange, so intake
 * durability is the broker's rather than the request thread's (ADR-13 §Ingress Inversion).
 *
 * <p>Channel-scoped {@code invoke + waitForConfirmsOrDie}, so a nack, a lost ack, an unroutable
 * return, or a connection error all reach the caller as an exception rather than a silent drop.
 * Consumers dedup by {@code enrollmentId} (ADR-13).
 */
@Slf4j
@Component
public class EnrollmentIntakePublisher {
    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutMillis;

    EnrollmentIntakePublisher(RabbitTemplate rabbitTemplate, AmqpProperties amqpProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutMillis = amqpProperties.confirmTimeout().toMillis();
    }

    public void publish(EnrollmentEvent event) {
        var correlation = new CorrelationData(event.enrollmentId());

        rabbitTemplate.invoke(ops -> {
            ops.convertAndSend(AmqpConfig.ENROLLMENT_INTAKE_EXCHANGE,
                    AmqpConfig.ENROLLMENT_INTAKE_ROUTING_KEY, event, correlation);
            ops.waitForConfirmsOrDie(confirmTimeoutMillis);
            return Boolean.TRUE;
        });

        var returned = correlation.getReturned();
        if (returned != null) {
            throw new AmqpException(
                    "Enrollment intake unroutable: exchange=" + returned.getExchange()
                            + " routingKey=" + returned.getRoutingKey()
                            + " replyText=" + returned.getReplyText());
        }
        log.info("Published enrollment intake enrollmentId={}", event.enrollmentId());
    }

}
