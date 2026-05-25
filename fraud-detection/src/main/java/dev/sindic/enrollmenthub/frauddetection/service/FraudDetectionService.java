package dev.sindic.enrollmenthub.frauddetection.service;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.events.FraudCheckResult;
import dev.sindic.enrollmenthub.contracts.events.SignalOutcome;
import dev.sindic.enrollmenthub.frauddetection.amqp.FraudCheckResultPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Stub fraud-detection implementation: approves every enrollment unconditionally
 * ({@link SignalOutcome#OK}). It exists to exercise the full request/reply wiring end-to-end so a
 * real implementation — velocity checks, device fingerprinting, email-domain and IP-clustering
 * analysis (architecture.md §10.1) — can replace it with no decision-engine changes.
 */
@Service
@Slf4j
public class FraudDetectionService {

    private final FraudCheckResultPublisher publisher;

    FraudDetectionService(FraudCheckResultPublisher publisher) {
        this.publisher = publisher;
    }

    public void check(EnrollmentData enrollmentData) {
        log.info("Fraud check (stub) — approving unconditionally");
        publisher.publish(new FraudCheckResult(enrollmentData.enrollmentId(), SignalOutcome.OK));
    }
}
