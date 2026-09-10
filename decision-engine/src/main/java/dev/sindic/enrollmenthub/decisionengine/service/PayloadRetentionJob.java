package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/**
 * Enforces storage limitation on the enrollment payload (ADR-20): nulls {@code original_request}
 * once the decision event carrying it has been confirmed by the broker.
 *
 * <p><b>Why the payload is stored at all.</b> The decision event carries the full enrollment
 * downstream for account creation, and the engine has no other source for it — the intake message
 * is long acked and ADR-02 rules out asking the Account Service. So the row is where it lives, from
 * intake until the publisher confirm returns.
 *
 * <p><b>Why it can be erased after that.</b> The payload is an ingredient of the outbound event,
 * not part of the decision of record. Once {@code dispatched_at} is stamped the engine has no
 * reader for it: {@code claimUndispatched} selects {@code dispatched_at IS NULL} and
 * {@code markDispatched} is guarded on the same predicate, so a stamped row is never re-claimed or
 * re-published. What survives the strip — the ids, the decision, the settled signal map, the
 * timestamps — is the decision of record, and carries the {@code decisionId → enrollmentId}
 * linkage that exists nowhere else (ADR-17 withholds {@code enrollmentId} from the event).
 *
 * <p><b>No waiting period.</b> Nothing here reads the payload after the confirm, and nothing
 * recovers a message the broker accepted and then lost — no row is re-claimed once stamped, and no
 * reconciliation against the consumer exists. From the confirm onward the {@code
 * EnrollmentDecisionEvent} — the only message the payload is read to build — and its retention
 * belong to the Account Service, which owns the queue it lands on (ADR-13 §Channel Ownership). A
 * window would therefore hold personal data for a recovery this service neither performs nor owns.
 * The engine's own intake and check-request queues are unaffected: it owns those and their DLQs.
 * Operations can halt erasure with {@code decision-engine.retention.enabled}.
 *
 * <p><b>What it cannot reach.</b> The claim predicate is the exact complement of every state with
 * a live reader. A row the timeout poller can claim has {@code decision_result IS NULL}, therefore
 * {@code dispatched_at IS NULL}, therefore its payload is untouchable at any age — that protection
 * is structural, not a rule this job has to remember.
 *
 * <p><b>What it does not cover.</b> Backups and WAL still hold pre-strip row versions until they
 * age out of their own retention; erasure there is bounded by the backup cycle, not by this job
 * (ADR-20 §Residual exposure). Deletion of the stripped row itself is deliberately not implemented
 * — see ADR-20 for why the surviving row is retained rather than aged out.
 */
@Slf4j
@Component
@EnableConfigurationProperties(RetentionProperties.class)
public class PayloadRetentionJob {

    static final String STRIPPED_METRIC = "decisionengine.payload.stripped";

    private final EnrollmentRepository repository;
    private final Counter stripped;
    private final boolean enabled;
    private final int batchSize;

    PayloadRetentionJob(EnrollmentRepository repository,
                        MeterRegistry registry,
                        RetentionProperties properties) {
        this.repository = repository;
        this.enabled = properties.enabled();
        this.batchSize = properties.batchSize();
        this.stripped = Counter.builder(STRIPPED_METRIC)
                .description("Enrollment payloads erased from dispatched correlation rows (ADR-20)")
                .register(registry);
    }

    /**
     * One pass on its own cadence ({@code decision-engine.retention.interval}), deliberately not a
     * third phase of {@link EnrollmentSweepJob}: that job merges its two phases because timeout
     * detection and the dispatch backstop sit in the same latency class, and retention does not.
     *
     * <p>Drains while a full batch comes back so a backlog clears in one wake-up. Each
     * batch is its own transaction on the repository, so an interruption leaves the rows it
     * already stripped stripped — the work is idempotent and has no ordering to preserve.
     *
     * <p>Failures are logged rather than propagated. Retention is a background obligation, not a
     * request path: a database hiccup should cost one pass, and the
     * {@code decisionengine.payload.oldest.age} gauge is what makes a persistent failure visible.
     */
    @Scheduled(fixedDelayString = "${decision-engine.retention.interval}")
    void stripDispatchedPayloads() {
        if (!enabled) {
            return;
        }
        try {
            int total = 0;
            int batch;
            do {
                batch = repository.stripDispatchedPayloads(batchSize);
                total += batch;
            } while (batch == batchSize);

            if (total > 0) {
                stripped.increment(total);
                log.info("Retention: erased {} delivered enrollment payload(s)", total);
            }
        } catch (RuntimeException ex) {
            log.error("Retention pass failed; payloads remain until the next pass", ex);
        }
    }
}
