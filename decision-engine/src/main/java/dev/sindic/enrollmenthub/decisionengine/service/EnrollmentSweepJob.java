package dev.sindic.enrollmenthub.decisionengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Single scheduled sweep that advances stuck correlation rows toward their terminal, delivered
 * state. Two phases per tick, each draining while a full batch comes back so a backlog clears in
 * one wake-up rather than one batch per interval:
 *
 * <ol>
 *   <li><b>Timeout (ADR-15)</b> — {@link EnrollmentService#processExpiredTimeouts} finalizes
 *       expired-and-undecided rows.</li>
 *   <li><b>Dispatch (ADR-17)</b> — {@link DecisionDispatcher#dispatchPending} publishes rows left
 *       in the outbox state, the backstop for the eager after-commit dispatch.</li>
 * </ol>
 *
 * <p><b>Why one job.</b> The eager dispatch owns steady-state delivery latency, which puts the
 * dispatch phase in the same loose-latency class as timeout detection — so two near-identical
 * {@code SKIP LOCKED} batch drains collapse onto one cadence. The merge is valid <em>because</em>
 * eager dispatch exists; remove that and the dispatch phase becomes latency-sensitive again and
 * should be split back onto its own schedule.
 *
 * <p><b>Ordering.</b> Timeouts run first so a row finalized this tick can be backstopped in the
 * same tick.
 *
 * <p><b>Isolation.</b> Each drain calls a distinct {@code @Transactional} service method per
 * batch, and the phases are wrapped independently here — a DB hiccup in the timeout phase must not
 * skip the dispatch phase, and a broker outage in the dispatch phase must not block timeouts.
 * Running the sweep on several instances is safe: every claim uses {@code SKIP LOCKED}, so
 * instances partition the work.
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
