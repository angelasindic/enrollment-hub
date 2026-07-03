package dev.sindic.enrollmenthub.decisionengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Single scheduled sweep that advances stuck correlation rows toward their terminal, delivered
 * state. It runs two phases per tick, in order:
 *
 * <ol>
 *   <li><b>Timeout phase (ADR-15)</b> — {@link EnrollmentService#processExpiredTimeouts} claims
 *       expired-and-undecided rows with {@code SKIP LOCKED}, applies the per-classification timeout
 *       policy, and finalizes each. Finalizing registers an after-commit eager dispatch (ADR-17),
 *       so a row decided here is usually delivered before phase 2 even looks at it.</li>
 *   <li><b>Dispatch phase (ADR-17)</b> — {@link DecisionDispatcher#dispatchPending} claims
 *       decided-but-undispatched rows with {@code SKIP LOCKED} and publishes them. This is the
 *       durability backstop for the eager dispatch, covering both handler-decided and
 *       timeout-decided rows whose eager publish failed or was lost to a crash.</li>
 * </ol>
 *
 * <p><b>Why one job.</b> The eager after-commit dispatch owns steady-state delivery latency, so the
 * dispatch phase is latency-insensitive — the same loose-latency class as timeout detection. Two
 * near-identical {@code SKIP LOCKED} batch drains collapse into one sweep on one cadence. This merge
 * is valid <em>because</em> eager dispatch exists; if that were removed, the dispatch phase would
 * become latency-sensitive again and should be split back onto its own tighter schedule.
 *
 * <p><b>Ordering.</b> Timeouts run before dispatch so a row finalized this tick can be backstopped
 * in the same tick rather than waiting a full interval.
 *
 * <p><b>Isolation.</b> The two phases keep <em>separate</em> transactions — each drain calls a
 * distinct {@code @Transactional} service method per batch. The phases are also wrapped
 * independently here, so a failure in the timeout phase (e.g. a DB hiccup) does not skip the
 * dispatch phase, and a broker outage in the dispatch phase does not block timeout processing.
 * Running both phases on other decision-engine instances is safe: every claim uses {@code SKIP
 * LOCKED}, so instances partition the work into disjoint sets.
 *
 * <p>The drain loop keeps claiming while a full batch comes back, so a backlog accumulated during
 * downtime clears in one wake-up instead of one batch per interval.
 */
@Slf4j
@Component
@EnableConfigurationProperties(SweepProperties.class)
public class EnrollmentSweepJob {

    private final EnrollmentService enrollmentService;
    private final DecisionDispatcher dispatcher;
    private final Clock clock;
    private final int batchSize;

    EnrollmentSweepJob(EnrollmentService enrollmentService,
                       DecisionDispatcher dispatcher,
                       Clock clock,
                       SweepProperties properties) {
        this.enrollmentService = enrollmentService;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.batchSize = properties.batchSize();
    }

    @Scheduled(fixedDelayString = "${decision-engine.sweep.interval}")
    void sweep() {
        finalizeExpiredTimeouts();
        dispatchPendingDecisions();
    }

    private void finalizeExpiredTimeouts() {
        try {
            int finalized;
            do {
                finalized = enrollmentService.processExpiredTimeouts(clock.instant(), batchSize);
            } while (finalized == batchSize);
        } catch (RuntimeException ex) {
            // Contain the phase so the dispatch backstop still runs this tick.
            log.error("Sweep timeout phase failed; dispatch phase still runs", ex);
        }
    }

    private void dispatchPendingDecisions() {
        try {
            int dispatched;
            do {
                dispatched = dispatcher.dispatchPending(batchSize);
            } while (dispatched == batchSize);
        } catch (RuntimeException ex) {
            log.error("Sweep dispatch phase failed", ex);
        }
    }
}
