package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Publishes the ADR-17 stuck-outbox signal: the age of the oldest decided-but-undispatched
 * correlation row, evaluated per scrape via a single probe of the partial index behind
 * {@code EnrollmentRepository.findOldestUndispatchedDecidedAt}.
 *
 * <p>Zero is the healthy reading — the eager after-commit dispatch stamps rows within
 * milliseconds, so the outbox is normally empty. A growing value means both the eager trigger
 * and the sweep backstop are failing to deliver (the {@code StuckDecisionOutbox} alert in
 * {@code monitoring/prometheus/rules} fires past 5 minutes). The probe itself failing reports
 * {@code NaN}, never {@code 0} — "cannot measure" must not read as "healthy", or a database
 * outage would silence the very alert this gauge exists to drive.
 */
@Slf4j
@Configuration
public class OutboxMetricsConfig {

    static final String OUTBOX_AGE_METRIC = "decisionengine.outbox.oldest.age";

    @Bean
    Gauge outboxOldestAgeGauge(EnrollmentRepository repository, Clock clock, MeterRegistry registry) {
        return Gauge.builder(OUTBOX_AGE_METRIC, () -> oldestUndispatchedAgeSeconds(repository, clock))
                .baseUnit("seconds")
                .description("Age of the oldest decided-but-undispatched enrollment decision (ADR-17 outbox)")
                .register(registry);
    }

    private static double oldestUndispatchedAgeSeconds(EnrollmentRepository repository, Clock clock) {
        try {
            return repository.findOldestUndispatchedDecidedAt()
                    .map(oldest -> (double) Duration.between(oldest, clock.instant()).toSeconds())
                    .orElse(0.0);
        } catch (Exception e) {
            log.warn("Could not read outbox age", e);
            return Double.NaN;
        }
    }
}
