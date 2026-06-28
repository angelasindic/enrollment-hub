package dev.sindic.enrollmenthub.decisionengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Scheduled timeout poller (ADR-15). On a fixed delay it drains expired-and-undecided correlation
 * rows in batches, delegating each batch to {@link EnrollmentService#processExpiredTimeouts} — which
 * claims rows with {@code SKIP LOCKED}, so running this on several decision-engine instances is safe
 * (each claims a disjoint set).
 *
 * <p>The drain loop keeps claiming while a full batch comes back, so a backlog accumulated during
 * downtime is cleared in one wake-up instead of one batch per interval. Each batch is its own
 * transaction (the boundary is on {@code processExpiredTimeouts}), bounding lock duration.
 *
 * <p>Detection and scheduling only — the fail-open/fail-closed policy and the decision emission live
 * in {@link EnrollmentService}. This component owns the schedule and the drain loop, nothing else.
 */
@Slf4j
@Component
@EnableConfigurationProperties(TimeoutPollerProperties.class)
public class TimeoutPoller {

    private final EnrollmentService enrollmentService;
    private final Clock clock;
    private final int batchSize;

    TimeoutPoller(EnrollmentService enrollmentService, Clock clock, TimeoutPollerProperties properties) {
        this.enrollmentService = enrollmentService;
        this.clock = clock;
        this.batchSize = properties.batchSize();
    }

    @Scheduled(fixedDelayString = "${decision-engine.timeout-poller.interval}")
    void pollExpiredTimeouts() {
        int finalized;
        do {
            finalized = enrollmentService.processExpiredTimeouts(clock.instant(), batchSize);
        } while (finalized == batchSize);
    }
}
