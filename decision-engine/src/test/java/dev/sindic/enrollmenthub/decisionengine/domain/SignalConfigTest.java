package dev.sindic.enrollmenthub.decisionengine.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SignalConfigTest {

    @Nested
    class InitializeForCreditCard {

        private final Map<SignalConfig, SignalState> signals =
                SignalConfig.initializeFor(PaymentType.CREDIT_CARD);

        @Test
        void geoScoreIsPending() {
            assertThat(signals.get(SignalConfig.GEO_SCORE).processingState())
                    .isEqualTo(SignalProcessingState.PENDING);
        }

        @Test
        void fraudCheckIsPending() {
            assertThat(signals.get(SignalConfig.FRAUD_CHECK).processingState())
                    .isEqualTo(SignalProcessingState.PENDING);
        }

        @Test
        void containsOnlyApplicableSignals() {
            assertThat(signals).containsOnlyKeys(SignalConfig.GEO_SCORE, SignalConfig.FRAUD_CHECK);
        }
    }

    @Nested
    class InitializeForInvoice {

        private final Map<SignalConfig, SignalState> signals =
                SignalConfig.initializeFor(PaymentType.INVOICE);

        @Test
        void fraudCheckIsPending() {
            assertThat(signals.get(SignalConfig.FRAUD_CHECK).processingState())
                    .isEqualTo(SignalProcessingState.PENDING);
        }

        @Test
        void geoScoreAbsent() {
            assertThat(signals).doesNotContainKey(SignalConfig.GEO_SCORE);
        }

        @Test
        void containsOnlyApplicableSignals() {
            assertThat(signals).containsOnlyKeys(SignalConfig.FRAUD_CHECK);
        }
    }

    @Nested
    class AllSettled {

        @Test
        void falseWhenPendingSignalsRemain() {
            var signals = SignalConfig.initializeFor(PaymentType.CREDIT_CARD);
            assertThat(SignalConfig.allSettled(signals)).isFalse();
        }

        @Test
        void trueWhenAllSignalsAreSettled() {
            var signals = SignalConfig.initializeFor(PaymentType.CREDIT_CARD);
            signals.put(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.LOW));
            signals.put(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));
            assertThat(SignalConfig.allSettled(signals)).isTrue();
        }

        @Test
        void failedCountsAsTerminal() {
            var signals = SignalConfig.initializeFor(PaymentType.CREDIT_CARD);
            signals.put(SignalConfig.GEO_SCORE, SignalState.failed());
            signals.put(SignalConfig.FRAUD_CHECK, SignalState.failed());
            assertThat(SignalConfig.allSettled(signals)).isTrue();
        }

        @Test
        void invoiceCompleteWhenFraudSettles() {
            var signals = SignalConfig.initializeFor(PaymentType.INVOICE);
            signals.put(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));
            assertThat(SignalConfig.allSettled(signals)).isTrue();
        }

        @Test
        void partialSettlementIsNotComplete() {
            var signals = SignalConfig.initializeFor(PaymentType.CREDIT_CARD);
            signals.put(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.LOW));
            // FRAUD_CHECK still PENDING
            assertThat(SignalConfig.allSettled(signals)).isFalse();
        }
    }

    @Nested
    class RouteMetadata {

        @Test
        void geoScoreApplicableToCreditCardOnly() {
            assertThat(SignalConfig.GEO_SCORE.applicableTo(PaymentType.CREDIT_CARD)).isTrue();
            assertThat(SignalConfig.GEO_SCORE.applicableTo(PaymentType.INVOICE)).isFalse();
        }

        @Test
        void fraudCheckApplicableToBothRoutes() {
            assertThat(SignalConfig.FRAUD_CHECK.applicableTo(PaymentType.CREDIT_CARD)).isTrue();
            assertThat(SignalConfig.FRAUD_CHECK.applicableTo(PaymentType.INVOICE)).isTrue();
        }

        @Test
        void classificationsAreCorrect() {
            assertThat(SignalConfig.GEO_SCORE.classification()).isEqualTo(GateClassification.SCORING_SIGNAL);
            assertThat(SignalConfig.FRAUD_CHECK.classification()).isEqualTo(GateClassification.BEST_EFFORT);
        }
    }
}
