package dev.sindic.enrollmenthub.decisionengine.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pairing between a classification and the shape of state it can hold. ADR-14 §Classifications
 * lists result shape as the third property a classification carries; {@link
 * GateClassification#admits} is what makes it checkable rather than conventional.
 */
class GateClassificationTest {

    private static final SignalState CHECKED = new SignalState.Checked(CheckOutcome.FAILED);
    private static final SignalState SCORED = new SignalState.Scored(RiskLevel.EXTREME);

    @Test
    void checkStyleClassificationsAdmitAVerdict() {
        assertThat(GateClassification.REQUIRED.admits(CHECKED)).isTrue();
        assertThat(GateClassification.BEST_EFFORT.admits(CHECKED)).isTrue();
    }

    @Test
    void scoringSignalAdmitsARiskTier() {
        assertThat(GateClassification.SCORING_SIGNAL.admits(SCORED)).isTrue();
    }

    @Test
    void aScoringSignalCannotHoldAVerdict() {
        // Aggregation's SCORING_SIGNAL branch inspects Scored only, so a verdict here would match
        // nothing and the signal would contribute nothing — silently.
        assertThat(GateClassification.SCORING_SIGNAL.admits(CHECKED)).isFalse();
    }

    @Test
    void aCheckStyleSignalCannotHoldARiskTier() {
        assertThat(GateClassification.REQUIRED.admits(SCORED)).isFalse();
        assertThat(GateClassification.BEST_EFFORT.admits(SCORED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(GateClassification.class)
    void everyClassificationAdmitsTheResultlessStates(GateClassification classification) {
        // Pending, NoResult and NotExecuted say a signal produced nothing. Nothing has no shape,
        // so they belong to any classification.
        assertThat(classification.admits(new SignalState.Pending())).isTrue();
        assertThat(classification.admits(new SignalState.NoResult("geocoding_failed"))).isTrue();
        assertThat(classification.admits(new SignalState.NotExecuted("timeout"))).isTrue();
    }

    @ParameterizedTest
    @EnumSource(SignalConfig.class)
    void eachDeclaredSignalAdmitsWhatItsListenerProduces(SignalConfig signal) {
        // The pairing that holds today, asserted rather than assumed. Reclassifying a signal
        // without changing its listener breaks this before it reaches a decision.
        var produced = signal.classification() == GateClassification.SCORING_SIGNAL ? SCORED : CHECKED;

        assertThat(signal.classification().admits(produced))
                .as("%s is %s", signal, signal.classification())
                .isTrue();
    }
}
