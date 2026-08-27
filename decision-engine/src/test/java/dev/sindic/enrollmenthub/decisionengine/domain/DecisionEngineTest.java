package dev.sindic.enrollmenthub.decisionengine.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionEngineTest {

    private static Map<SignalConfig, SignalState> signals(SignalConfig signal, SignalState state) {
        var map = new EnumMap<SignalConfig, SignalState>(SignalConfig.class);
        map.put(signal, state);
        return map;
    }

    private static Map<SignalConfig, SignalState> signals(SignalConfig first, SignalState firstState,
                                                          SignalConfig second, SignalState secondState) {
        var map = signals(first, firstState);
        map.put(second, secondState);
        return map;
    }

    /** Credit card with both signals settled. */
    private static Map<SignalConfig, SignalState> creditCard(SignalOutcome fraud, RiskLevel geo) {
        return signals(SignalConfig.FRAUD_CHECK, SignalState.settled(fraud),
                       SignalConfig.GEO_SCORE,   SignalState.settled(geo));
    }

    /** Credit card with fraud settled, geo timed out (FAILED). */
    private static Map<SignalConfig, SignalState> creditCardGeoFailed(SignalOutcome fraud) {
        return signals(SignalConfig.FRAUD_CHECK, SignalState.settled(fraud),
                       SignalConfig.GEO_SCORE,   SignalState.failed());
    }

    /** Credit card with geo settled, fraud timed out (FAILED). */
    private static Map<SignalConfig, SignalState> creditCardFraudFailed(RiskLevel geo) {
        return signals(SignalConfig.GEO_SCORE,   SignalState.settled(geo),
                       SignalConfig.FRAUD_CHECK, SignalState.failed());
    }

    /** Invoice with fraud settled. */
    private static Map<SignalConfig, SignalState> invoice(SignalOutcome fraud) {
        return signals(SignalConfig.FRAUD_CHECK, SignalState.settled(fraud));
    }

    private static EnrollmentDecisionResult evaluate(Map<SignalConfig, SignalState> signals) {
        return DecisionEngine.evaluate(signals, UUID.randomUUID());
    }

    // ── completeness guard ────────────────────────────────────────────────────

    @Test
    void evaluate_incompleteProcess_throwsIllegalState() {
        var enrollmentId = UUID.randomUUID();
        var incomplete = SignalConfig.initializeFor(PaymentType.CREDIT_CARD);
        assertThatThrownBy(() -> DecisionEngine.evaluate(incomplete, enrollmentId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(enrollmentId.toString());
    }

    @Test
    void evaluate_incompleteInvoice_throwsIllegalState() {
        var incomplete = SignalConfig.initializeFor(PaymentType.INVOICE);
        assertThatThrownBy(() -> DecisionEngine.evaluate(incomplete, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void evaluate_partiallySettledMap_namesTheOffendingSignal() {
        // One signal settled, one still PENDING — the guard must identify which.
        var enrollmentId = UUID.randomUUID();
        var partial = signals(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK),
                              SignalConfig.GEO_SCORE,   SignalState.pending());

        assertThatThrownBy(() -> DecisionEngine.evaluate(partial, enrollmentId))
                .isInstanceOf(AggregationPreconditionException.class)
                .hasMessageContaining("GEO_SCORE")
                .hasMessageContaining(enrollmentId.toString());
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
            var settled = signals(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.FAILED),
                                  SignalConfig.GEO_SCORE,   SignalState.failed());
            assertThat(evaluate(settled).decision()).isEqualTo(REJECTED);
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
            var timedOut = signals(SignalConfig.FRAUD_CHECK, SignalState.failed(),
                                   SignalConfig.GEO_SCORE,   SignalState.failed());
            assertThat(evaluate(timedOut).decision()).isEqualTo(APPROVED);
        }

        @Test
        void geoSettledWithoutResult_fraudOk_approved() {
            // Geocoding failure → settled without score → fail-open
            var settled = signals(SignalConfig.GEO_SCORE,   SignalState.settledWithoutResult("geocoding_failed"),
                                  SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));
            assertThat(evaluate(settled).decision()).isEqualTo(APPROVED);
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
            assertThat(evaluate(signals(SignalConfig.FRAUD_CHECK, SignalState.failed())).decision())
                    .isEqualTo(APPROVED);
        }
    }

    // ── ADR-14 compliance property ───────────────────────────────────────────

    /**
     * ADR-14 compliance — asymmetric aggregation property.
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
