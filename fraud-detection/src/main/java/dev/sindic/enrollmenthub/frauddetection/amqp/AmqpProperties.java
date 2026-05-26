package dev.sindic.enrollmenthub.frauddetection.amqp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for the fraud-detection AMQP publisher.
 *
 * @param confirmTimeout how long the listener thread blocks on a publisher confirm before
 *                       declaring the publish failed and letting the retry interceptor re-invoke
 *                       the listener. Default: 5s.
 */
@ConfigurationProperties(prefix = "fraud-detection.amqp")
public record AmqpProperties(@DefaultValue("5s") Duration confirmTimeout) {
}
