package dev.sindic.enrollmenthub.geoscoring.amqp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the geo-scoring AMQP publisher.
 *
 * @param confirmTimeout how long a listener thread blocks on a publisher confirm before
 *                       declaring the publish failed and letting the retry interceptor
 *                       re-invoke the listener. Must sit between normal confirm jitter
 *                       (sub-second on a healthy broker, multi-second during reconnects
 *                       or broker failover) and the listener's own retry budget
 *                       ({@code MAX_INTERVAL} in {@link AmqpConfig}, currently 10s).
 *                       Default: 5s.
 */
@ConfigurationProperties(prefix = "geo-scoring.amqp")
public record AmqpProperties(Duration confirmTimeout) {

    public AmqpProperties {
        if (confirmTimeout == null) {
            confirmTimeout = Duration.ofSeconds(5);
        }
    }
}
