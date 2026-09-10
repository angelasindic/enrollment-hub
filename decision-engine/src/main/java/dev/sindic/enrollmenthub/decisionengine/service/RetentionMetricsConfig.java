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
 * Publishes the ADR-20 storage-limitation signal: the age of the oldest correlation row still
 * holding an enrollment payload, evaluated per scrape against
 * {@code EnrollmentRepository.findOldestHeldPayloadCreatedAt}.
 *
 * <p>This is the compliance reading rather than a job statistic. {@link PayloadRetentionJob}'s
 * counter says how much was erased; this says how long personal data has actually been held, which
 * is the claim §8.2 makes and the one an audit would test. It reads the same whether the cause is
 * a stalled retention job, a decision stuck in the outbox, or a row the timeout poller never
 * finalized — all three are the service holding PII longer than it said it would.
 *
 * <p>A healthy value is bounded by the decision and delivery of the enrollment plus one retention
 * pass. The probe failing
 * reports {@code NaN}, never {@code 0} — "cannot measure" must not read as "holding nothing", or a
 * database outage would silence the alert this gauge exists to drive (same convention as
 * {@link OutboxMetricsConfig}).
 */
@Slf4j
@Configuration
public class RetentionMetricsConfig {

    static final String PAYLOAD_AGE_METRIC = "decisionengine.payload.oldest.age";

    @Bean
    Gauge oldestHeldPayloadAgeGauge(EnrollmentRepository repository, Clock clock, MeterRegistry registry) {
        return Gauge.builder(PAYLOAD_AGE_METRIC, () -> oldestHeldPayloadAgeSeconds(repository, clock))
                .baseUnit("seconds")
                .description("Age of the oldest correlation row still holding an enrollment payload (ADR-20)")
                .register(registry);
    }

    private static double oldestHeldPayloadAgeSeconds(EnrollmentRepository repository, Clock clock) {
        try {
            return repository.findOldestHeldPayloadCreatedAt()
                    .map(oldest -> (double) Duration.between(oldest, clock.instant()).toSeconds())
                    .orElse(0.0);
        } catch (Exception e) {
            log.warn("Could not read held-payload age", e);
            return Double.NaN;
        }
    }
}
