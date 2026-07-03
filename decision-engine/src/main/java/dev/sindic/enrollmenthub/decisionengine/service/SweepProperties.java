package dev.sindic.enrollmenthub.decisionengine.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tuning for the {@link EnrollmentSweepJob} — the single background sweep that runs the
 * timeout-finalize phase (ADR-15) and the dispatch backstop phase (ADR-17).
 *
 * @param interval  fixed delay between sweep passes. One cadence drives both phases: timeout
 *                  detection wants promptness, and the dispatch phase is a loose backstop (the
 *                  after-commit eager dispatch owns steady-state delivery latency, ADR-17), so
 *                  the tighter timeout cadence satisfies both. Also read by the {@code @Scheduled}
 *                  annotation on {@link EnrollmentSweepJob} via this property key.
 * @param batchSize rows claimed per phase transaction. Small batches bound row-lock duration
 *                  (ADR-15 §Lock variant) and the duplicate blast radius of a mid-batch publish
 *                  failure in the dispatch phase; each phase's drain loop clears any remaining backlog.
 */
@ConfigurationProperties(prefix = "decision-engine.sweep")
public record SweepProperties(
        @DefaultValue("10s") Duration interval,
        @DefaultValue("100") int batchSize) {
}
