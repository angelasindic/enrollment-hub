package dev.sindic.enrollmenthub.decisionengine.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tuning for the timeout poller (ADR-15).
 *
 * @param interval  fixed delay between poll passes. Also read by the {@code @Scheduled} annotation
 *                  on {@link TimeoutPoller} via the same property key, so this field documents and
 *                  validates the value the schedule uses.
 * @param batchSize rows claimed per poll transaction. Small batches bound how long the poller holds
 *                  row locks (ADR-15 §Lock variant); the drain loop clears any remaining backlog.
 */
@ConfigurationProperties(prefix = "decision-engine.timeout-poller")
public record TimeoutPollerProperties(
        @DefaultValue("10s") Duration interval,
        @DefaultValue("100") int batchSize) {
}
