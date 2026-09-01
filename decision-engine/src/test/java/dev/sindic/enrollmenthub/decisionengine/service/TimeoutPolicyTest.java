package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.decisionengine.domain.DecisionEngine;
import dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult;
import dev.sindic.enrollmenthub.decisionengine.domain.GateClassification;
import dev.sindic.enrollmenthub.decisionengine.domain.PaymentType;
import dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalOutcome;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the ADR-15 per-classification timeout policy applied by
 * {@link EnrollmentService#applyTimeoutPolicy}, the transition the timeout poller runs
 * before finalizing an expired enrollment.
 *
 * <p>The policy is classification-driven, not uniform: {@code BEST_EFFORT} and
 * {@code SCORING_SIGNAL} fail open, {@code REQUIRED} fails closed. No current
 * {@link SignalConfig} is {@code REQUIRED}, so {@link #requiredSignal_failsClosed()}
 * pins the fail-closed branch against a synthetic map to keep it covered until one exists.
 */
class TimeoutPolicyTest {

    private static Map<SignalConfig, SignalState> pendingFor(PaymentType paymentType) {
        return new EnumMap<>(SignalConfig.initializeFor(paymentType));
    }

    @Test
    void creditCard_allPending_bothFailOpen() {
        var timedOut = EnrollmentService.applyTimeoutPolicy(pendingFor(PaymentType.CREDIT_CARD));

        assertThat(timedOut.get(SignalConfig.GEO_SCORE))
                .isEqualTo(new SignalState.NotExecuted("timeout"));
        assertThat(timedOut.get(SignalConfig.FRAUD_CHECK))
                .isEqualTo(new SignalState.NotExecuted("timeout"));
        assertThat(SignalConfig.allSettled(timedOut)).isTrue();
    }

    @Test
    void invoice_onlyFraudApplicable_failsOpen() {
        var timedOut = EnrollmentService.applyTimeoutPolicy(pendingFor(PaymentType.INVOICE));

        assertThat(timedOut).containsOnlyKeys(SignalConfig.FRAUD_CHECK);
        assertThat(timedOut.get(SignalConfig.FRAUD_CHECK))
                .isEqualTo(new SignalState.NotExecuted("timeout"));
        assertThat(SignalConfig.allSettled(timedOut)).isTrue();
    }

    @Test
    void alreadySettledSignal_isLeftUntouched() {
        var signals = pendingFor(PaymentType.CREDIT_CARD);
        signals.put(SignalConfig.GEO_SCORE, new SignalState.Scored(RiskLevel.HIGH));

        var timedOut = EnrollmentService.applyTimeoutPolicy(signals);

        assertThat(timedOut.get(SignalConfig.GEO_SCORE))
                .isEqualTo(new SignalState.Scored(RiskLevel.HIGH));
        assertThat(timedOut.get(SignalConfig.FRAUD_CHECK))
                .isEqualTo(new SignalState.NotExecuted("timeout"));
    }

    @Test
    void creditCard_fullTimeout_evaluatesToApproved() {
        // No signal is REQUIRED today, so a wholly unanswered credit-card enrollment
        // fails every gate open and still approves.
        var timedOut = EnrollmentService.applyTimeoutPolicy(pendingFor(PaymentType.CREDIT_CARD));

        assertThat(DecisionEngine.evaluate(timedOut, UUID.randomUUID()).decision())
                .isEqualTo(DecisionResult.APPROVED);
    }

    /**
     * Fail-closed branch: a still-{@code Pending} {@code REQUIRED} signal settles as
     * {@code Checked(FAILED)} rather than {@code NotExecuted}, which drives
     * {@link DecisionResult#REJECTED}. Skipped as unreachable — and the test
     * self-disables — until a {@code REQUIRED} signal is declared.
     */
    @Test
    void requiredSignal_failsClosed() {
        var required = java.util.Arrays.stream(SignalConfig.values())
                .filter(s -> s.classification() == GateClassification.REQUIRED)
                .findFirst();
        org.junit.jupiter.api.Assumptions.assumeTrue(required.isPresent(),
                "no REQUIRED SignalConfig declared yet");

        var signals = new EnumMap<SignalConfig, SignalState>(SignalConfig.class);
        signals.put(required.get(), new SignalState.Pending());

        var timedOut = EnrollmentService.applyTimeoutPolicy(signals);

        assertThat(timedOut.get(required.get()))
                .isEqualTo(new SignalState.Checked(SignalOutcome.FAILED));
    }
}
