package dev.sindic.enrollmenthub.decisionengine.amqp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for the decision-engine AMQP publisher.
 *
 * @param confirmTimeout how long the publish path blocks on a publisher confirm before
 *                       declaring the publish failed and throwing. Must sit between normal
 *                       confirm jitter (sub-second on a healthy broker, multi-second during
 *                       reconnects or broker failover) and any upstream retry budget.
 *                       Default: 5s.
 */
@ConfigurationProperties(prefix = "decision-engine.amqp")
public record AmqpProperties(@DefaultValue("5s") Duration confirmTimeout) {
}
