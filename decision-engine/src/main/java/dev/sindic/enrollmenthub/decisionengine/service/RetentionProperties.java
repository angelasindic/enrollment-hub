package dev.sindic.enrollmenthub.decisionengine.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tuning for {@link PayloadRetentionJob} — the pass that enforces storage limitation on
 * {@code original_request} (ADR-20).
 *
 * <p>Every key under {@code decision-engine.retention} appears here, so the record is a complete
 * picture of the namespace. {@code interval} is the exception to how they are used: the
 * schedule reads that key from the {@code Environment} through the {@code @Scheduled}
 * annotation on {@link PayloadRetentionJob}, not through this accessor, so its
 * {@code @DefaultValue} mirrors {@code application.yml} but does not itself govern the
 * cadence. Do not drop the component as unused — it is what documents the key.
 *
 * @param enabled   whether the job runs. A job that erases data unattended is one operations must
 *                  be able to stop without a deploy.
 * @param interval  fixed delay between retention passes. Read by the {@code @Scheduled}
 *                  annotation via the property key; why retention runs on its own cadence rather
 *                  than as a phase of the sweep is on
 *                  {@link PayloadRetentionJob#stripDispatchedPayloads()}.
 * @param batchSize rows stripped per statement. Small batches bound how long the {@code SKIP
 *                  LOCKED} claim holds row locks against live handlers; the job's drain loop clears
 *                  any backlog. Rejected below zero: the drain loop exits on a short batch
 *                  ({@code batch != batchSize}), so a configured {@code 0} would make every pass an
 *                  empty batch that equals the limit and spin forever.
 */
@ConfigurationProperties(prefix = "decision-engine.retention")
public record RetentionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("15m") Duration interval,
        @DefaultValue("500") int batchSize) {

    public RetentionProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException(
                    "decision-engine.retention.batch-size must be positive, was " + batchSize);
        }
    }
}
