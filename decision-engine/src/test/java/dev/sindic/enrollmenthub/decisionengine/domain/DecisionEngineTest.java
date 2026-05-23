package dev.sindic.enrollmenthub.decisionengine.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionEngineTest {

    private static final Instant NOW    = Instant.parse("2026-04-09T12:00:00Z");
    private static final Instant TIMEOUT = NOW.plusSeconds(60);

    private static EnrollmentCommand creditCardCommand() {
        return new EnrollmentCommand(UUID.randomUUID(), PaymentType.CREDIT_CARD, null, null, null);
    }

    private static EnrollmentCommand invoiceCommand() {
        return new EnrollmentCommand(UUID.randomUUID(), PaymentType.INVOICE, null, null, null);
    }

    /** Credit card with both signals settled. */
    private static EnrollmentProcess creditCard(SignalOutcome fraud, RiskLevel geo) {
        return EnrollmentProcess.start(UUID.randomUUID(), creditCardCommand(), NOW, TIMEOUT)
                .withSignalResult(SignalConfig.FRAUD_CHECK, SignalState.settled(fraud))
                .withSignalResult(SignalConfig.GEO_SCORE,   SignalState.settled(geo));
    }

    /** Credit card with fraud settled, geo timed out (FAILED). */
    private static EnrollmentProcess creditCardGeoFailed(SignalOutcome fraud) {
        return EnrollmentProcess.start(UUID.randomUUID(), creditCardCommand(), NOW, TIMEOUT)
                .withSignalResult(SignalConfig.FRAUD_CHECK, SignalState.settled(fraud))
                .withSignalResult(SignalConfig.GEO_SCORE,   SignalState.failed());
    }

    /** Credit card with geo settled, fraud timed out (FAILED). */
    private static EnrollmentProcess creditCardFraudFailed(RiskLevel geo) {
        return EnrollmentProcess.start(UUID.randomUUID(), creditCardCommand(), NOW, TIMEOUT)
                .withSignalResult(SignalConfig.GEO_SCORE,   SignalState.settled(geo))
                .withSignalResult(SignalConfig.FRAUD_CHECK, SignalState.failed());
    }

    /** Invoice with fraud settled. */
    private static EnrollmentProcess invoice(SignalOutcome fraud) {
        return EnrollmentProcess.start(UUID.randomUUID(), invoiceCommand(), NOW, TIMEOUT)
                .withSignalResult(SignalConfig.FRAUD_CHECK, SignalState.settled(fraud));
    }

    /**
     * Convenience adapter that lets the existing test bodies keep their
     * {@link EnrollmentProcess}-centric phrasing even after the production API
     * shifted to {@code (signals, requestId)}.
     */
    private static EnrollmentDecisionResult evaluate(EnrollmentProcess process) {
        return DecisionEngine.evaluate(process.signals(), process.requestId());
    }

    // ── evaluate() guard ──────────────────────────────────────────────────────

    @Test
    void evaluate_incompleteProcess_throwsIllegalState() {
        var incomplete = EnrollmentProcess.start(UUID.randomUUID(), creditCardCommand(), NOW, TIMEOUT);
        assertThatThrownBy(() -> evaluate(incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(incomplete.requestId().toString());
    }

    @Test
    void evaluate_incompleteInvoice_throwsIllegalState() {
        var incomplete = EnrollmentProcess.start(UUID.randomUUID(), invoiceCommand(), NOW, TIMEOUT);
        assertThatThrownBy(() -> evaluate(incomplete))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── aggregate() precondition guard ───────────────────────────────────────

    @Test
    void aggregate_pendingSignalInMap_throwsPreconditionException() {
        // Build a signal map where GEO_SCORE is still PENDING.
        // aggregate() is package-private to allow this guard to be tested
        // without bypassing the evaluate() completion check via reflection.
        var signals = new EnumMap<SignalConfig, SignalState>(SignalConfig.class);
        signals.put(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));
        signals.put(SignalConfig.GEO_SCORE,   SignalState.pending());

        assertThatThrownBy(() -> DecisionEngine.aggregate(signals))
                .isInstanceOf(AggregationPreconditionException.class)
                .hasMessageContaining("GEO_SCORE");
    }

    // ── CREDIT_CARD route ─────────────────────────────────────────────────────

    @Nested
    class CreditCardRoute {

        @Test
        void fraudOk_geoLow_approved() {
            assertThat(evaluate(creditCard(SignalOutcome.OK, RiskLevel.LOW)).decision())
                    .isEqualTo(APPROVED);
        }

        @Test
        void fraudOk_geoMedium_approved() {
            assertThat(evaluate(creditCard(SignalOutcome.OK, RiskLevel.MEDIUM)).decision())
                    .isEqualTo(APPROVED);
        }

        @Test
        void fraudOk_geoHigh_conditionalApproved() {
            assertThat(evaluate(creditCard(SignalOutcome.OK, RiskLevel.HIGH)).decision())
                    .isEqualTo(CONDITIONAL_APPROVED);
        }

        @Test
        void fraudOk_geoExtreme_conditionalApproved() {
            assertThat(evaluate(creditCard(SignalOutcome.OK, RiskLevel.EXTREME)).decision())
                    .isEqualTo(CONDITIONAL_APPROVED);
        }

        @Test
        void fraudFailed_geoLow_rejected() {
            assertThat(evaluate(creditCard(SignalOutcome.FAILED, RiskLevel.LOW)).decision())
                    .isEqualTo(REJECTED);
        }

        @Test
        void fraudFailed_geoHigh_rejected() {
            // REJECTED from BEST_EFFORT overrides CONDITIONAL_APPROVED from SCORING_SIGNAL
            assertThat(evaluate(creditCard(SignalOutcome.FAILED, RiskLevel.HIGH)).decision())
                    .isEqualTo(REJECTED);
        }

        @Test
        void fraudFailed_geoExtreme_rejected() {
            assertThat(evaluate(creditCard(SignalOutcome.FAILED, RiskLevel.EXTREME)).decision())
                    .isEqualTo(REJECTED);
        }

        @Test
        void fraudNoResult_geoLow_approved() {
            // NO_RESULT = ran but could not score — fail-open for BEST_EFFORT
            assertThat(evaluate(creditCard(SignalOutcome.NO_RESULT, RiskLevel.LOW)).decision())
                    .isEqualTo(APPROVED);
        }

        @Test
        void fraudNoResult_geoHigh_conditionalApproved() {
            assertThat(evaluate(creditCard(SignalOutcome.NO_RESULT, RiskLevel.HIGH)).decision())
                    .isEqualTo(CONDITIONAL_APPROVED);
        }

        @Test
        void geoTimedOut_fraudOk_approved() {
            assertThat(evaluate(creditCardGeoFailed(SignalOutcome.OK)).decision())
                    .isEqualTo(APPROVED);
        }

        @Test
        void geoTimedOut_fraudNoResult_approved() {
            assertThat(evaluate(creditCardGeoFailed(SignalOutcome.NO_RESULT)).decision())
                    .isEqualTo(APPROVED);
        }

        @Test
        void geoTimedOut_fraudFailed_rejected() {
            // Fraud FAILED outcome is explicit — REJECTED even though geo timed out
            var process = EnrollmentProcess.start(UUID.randomUUID(), creditCardCommand(), NOW, TIMEOUT)
                    .withSignalResult(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.FAILED))
                    .withSignalResult(SignalConfig.GEO_SCORE,   SignalState.failed());
            assertThat(evaluate(process).decision()).isEqualTo(REJECTED);
        }

        @Test
        void fraudTimedOut_geoLow_approved() {
            assertThat(evaluate(creditCardFraudFailed(RiskLevel.LOW)).decision())
                    .isEqualTo(APPROVED);
        }

        @Test
        void fraudTimedOut_geoMedium_approved() {
            assertThat(evaluate(creditCardFraudFailed(RiskLevel.MEDIUM)).decision())
                    .isEqualTo(APPROVED);
        }

        @Test
        void fraudTimedOut_geoHigh_conditionalApproved() {
            assertThat(evaluate(creditCardFraudFailed(RiskLevel.HIGH)).decision())
                    .isEqualTo(CONDITIONAL_APPROVED);
        }

        @Test
        void fraudTimedOut_geoExtreme_conditionalApproved() {
            assertThat(evaluate(creditCardFraudFailed(RiskLevel.EXTREME)).decision())
                    .isEqualTo(CONDITIONAL_APPROVED);
        }

        @Test
        void bothTimedOut_approved() {
            var process = EnrollmentProcess.start(UUID.randomUUID(), creditCardCommand(), NOW, TIMEOUT)
                    .withTimeout();
            assertThat(evaluate(process).decision()).isEqualTo(APPROVED);
        }

        @Test
        void geoSettledWithoutResult_fraudOk_approved() {
            // Geocoding failure → settled without score → fail-open
            var process = EnrollmentProcess.start(UUID.randomUUID(), creditCardCommand(), NOW, TIMEOUT)
                    .withSignalResult(SignalConfig.GEO_SCORE,   SignalState.settledWithoutResult("geocoding_failed"))
                    .withSignalResult(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));
            assertThat(evaluate(process).decision()).isEqualTo(APPROVED);
        }
    }

    // ── INVOICE route ─────────────────────────────────────────────────────────

    @Nested
    class InvoiceRoute {

        @Test
        void fraudOk_approved() {
            assertThat(evaluate(invoice(SignalOutcome.OK)).decision()).isEqualTo(APPROVED);
        }

        @Test
        void fraudFailed_rejected() {
            assertThat(evaluate(invoice(SignalOutcome.FAILED)).decision()).isEqualTo(REJECTED);
        }

        @Test
        void fraudNoResult_approved() {
            assertThat(evaluate(invoice(SignalOutcome.NO_RESULT)).decision()).isEqualTo(APPROVED);
        }

        @Test
        void fraudTimedOut_approved() {
            var process = EnrollmentProcess.start(UUID.randomUUID(), invoiceCommand(), NOW, TIMEOUT)
                    .withTimeout();
            assertThat(evaluate(process).decision()).isEqualTo(APPROVED);
        }
    }

    // ── ADR-018 compliance property ───────────────────────────────────────────

    /**
     * ADR-018 compliance — asymmetric aggregation property.
     *
     * <p>For every {@link RiskLevel}, with {@link SignalOutcome#OK} from the fraud check
     * and both signals {@link SignalProcessingState#SETTLED}, the result is
     * {@code APPROVED} or {@code CONDITIONAL_APPROVED} — never {@code REJECTED}.
     * Proves that a {@link GateClassification#SCORING_SIGNAL} cannot drive rejection
     * regardless of its risk level, even when the authoritative check actively passes.
     */
    @ParameterizedTest
    @EnumSource(RiskLevel.class)
    void scoringSignal_cannotDriveRejected(RiskLevel level) {
        // Both signals SETTLED: fraud OK, geo at every possible risk level.
        var result = evaluate(creditCard(SignalOutcome.OK, level));
        assertThat(result.decision()).isNotEqualTo(REJECTED);
    }

    // ── Priority resolution ───────────────────────────────────────────────────

    @Test
    void rejected_overrides_conditionalApproved() {
        // FRAUD_CHECK FAILED (→ rejected) + GEO_SCORE EXTREME (→ reviewRequired)
        // REJECTED must win
        assertThat(evaluate(creditCard(SignalOutcome.FAILED, RiskLevel.EXTREME)).decision())
                .isEqualTo(REJECTED);
    }
}
